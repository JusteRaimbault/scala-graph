package scalax.collection

import language.{higherKinds, implicitConversions}
import scala.annotation.{switch, tailrec}
import collection.mutable.{ArrayBuffer, ArraySeq, ArrayStack => Stack, ListBuffer, Queue,
                           PriorityQueue, Set => MutableSet, Map => MutableMap}

import collection.Abstract
import GraphPredef.{EdgeLikeIn, GraphParam, GraphParamIn, GraphParamOut,
                    NodeIn, NodeOut, EdgeIn, EdgeOut}
import GraphEdge.{EdgeLike}
import mutable.{ArraySet, ExtBitSet}
import scalax.collection.mutable.ExtBitSet
import scalax.collection.mutable.ExtBitSet

trait GraphTraversalImpl[N, E[X] <: EdgeLikeIn[X]]
  extends GraphTraversal[N,E]
  with State[N,E]
{ selfGraph =>

  import GraphTraversalImpl._
  import GraphTraversal.VisitorReturn._
  import GraphTraversal._
  import State._
  
  override def findCycle(nodeFilter : (NodeT) => Boolean       = anyNode,
                         edgeFilter : (EdgeT) => Boolean       = anyEdge,
                         maxDepth   :  Int                     = 0,
                         nodeVisitor: (NodeT) => VisitorReturn = noNodeAction,
                         edgeVisitor: (EdgeT) => Unit          = noEdgeAction,
                         ordering   : ElemOrdering             = noOrdering): Option[Cycle] =
    if (order == 0) None
    else {
      val traversal = new Traversal(Successors, nodeFilter, edgeFilter,
                                                nodeVisitor, edgeVisitor, ordering)
      withHandles(2) { handles => 
        implicit val visitedHandle = handles(0) 
        for (node <- nodes if ! node.visited) {
          val res = traversal.depthFirstSearchWGB(node, globalState = handles) 
          if (res._1.isDefined)
            return cycle(res, edgeFilter)
        }
      }
      None
    }
  protected type CycleStackElem = (NodeT, Iterable[EdgeT])
  final protected def cycle(results: (Option[NodeT], Stack[CycleStackElem]),
                            edgeFilter : (EdgeT) => Boolean): Option[Cycle] = {
    val (start, stack) = results
    start map { n: NodeT =>
      def toNode(elem: CycleStackElem) = elem._1
      def doWhile(elem: CycleStackElem): Boolean = elem._1 ne n
      val reverse: ReverseStackTraversable[CycleStackElem, NodeT] = {
          val enclosing: Array[Option[CycleStackElem]] = {
            val end = Some((n, Nil))
            Array[Option[CycleStackElem]](end, end)
          }
          new ReverseStackTraversable[CycleStackElem, NodeT](
              stack, toNode, Some(doWhile), enclosing)
      }
      if (selfGraph.isDirected) {
        new AnyEdgeLazyCycle  (reverse, edgeFilter)
      } else {
        new MultiEdgeLazyCycle(reverse, edgeFilter)
      }
    }
  }
    
  type NodeT <: InnerNodeTraversalImpl
  trait InnerNodeTraversalImpl extends super.InnerNodeLike with InnerNodeState
  { this: NodeT =>
    override def findSuccessor(pred: (NodeT) => Boolean,
                               nodeFilter : (NodeT) => Boolean       = anyNode,
                               edgeFilter : (EdgeT) => Boolean       = anyEdge,
                               nodeVisitor: (NodeT) => VisitorReturn = noNodeAction,
                               edgeVisitor: (EdgeT) => Unit          = noEdgeAction,
                               ordering   : ElemOrdering             = noOrdering): Option[NodeT] =
    {
      new Traversal(Successors, nodeFilter, edgeFilter, nodeVisitor, edgeVisitor, ordering).
          depthFirstSearch(this, pred, 0)
    }
    override def findPredecessor(pred: (NodeT) => Boolean,
                                 nodeFilter : (NodeT) => Boolean       = anyNode,
                                 edgeFilter : (EdgeT) => Boolean       = anyEdge,
                                 nodeVisitor: (NodeT) => VisitorReturn = noNodeAction,
                                 edgeVisitor: (EdgeT) => Unit          = noEdgeAction,
                                 ordering   : ElemOrdering             = noOrdering): Option[NodeT] =
    {
      new Traversal(Predecessors, nodeFilter, edgeFilter, nodeVisitor, edgeVisitor, ordering).
          depthFirstSearch(this, pred = pred)
    }
    override def findConnected(pred: (NodeT) => Boolean,
                               nodeFilter : (NodeT) => Boolean       = anyNode,
                               edgeFilter : (EdgeT) => Boolean       = anyEdge,
                               nodeVisitor: (NodeT) => VisitorReturn = noNodeAction,
                               edgeVisitor: (EdgeT) => Unit          = noEdgeAction,
                               ordering   : ElemOrdering             = noOrdering): Option[NodeT] =
    {
      new Traversal(AnyConnected, nodeFilter, edgeFilter, nodeVisitor, edgeVisitor, ordering).
          depthFirstSearch(this, pred)
    }
    override def pathUntil(pred: (NodeT) => Boolean,
                           nodeFilter : (NodeT) => Boolean       = anyNode,
                           edgeFilter : (EdgeT) => Boolean       = anyEdge,
                           nodeVisitor: (NodeT) => VisitorReturn = noNodeAction,
                           edgeVisitor: (EdgeT) => Unit          = noEdgeAction,
                           ordering   : ElemOrdering             = noOrdering): Option[Path] = {
      val (target, path) = 
          new Traversal(Successors, nodeFilter, edgeFilter, nodeVisitor, edgeVisitor, ordering).
          _depthFirstSearch(this, pred)
      target map { _ =>
        new AnyEdgeLazyPath(
          new ReverseStackTraversable[(NodeT, Int), NodeT]
              (path, (elem: (NodeT, Int)) => elem._1),
          edgeFilter)
      }
    }
    override def shortestPathTo(to: NodeT,
                                nodeFilter : (NodeT) => Boolean       = anyNode,
                                edgeFilter : (EdgeT) => Boolean       = anyEdge,
                                nodeVisitor: (NodeT) => VisitorReturn = noNodeAction,
                                edgeVisitor: (EdgeT) => Unit          = noEdgeAction,
                                ordering   : ElemOrdering             = noOrdering): Option[Path] =
    {
      withHandle() { implicit visitedHandle => 
        @inline def visited(n: NodeT) = n.visited
  
        type NodeWeight = (NodeT,Long)
        val dest      = MutableMap[NodeT,Long](this -> 0L)
        val mapToPred = MutableMap[NodeT,NodeT]()
        val traversal = new Traversal(Successors, nodeFilter, edgeFilter,
                                      nodeVisitor, edgeVisitor, ordering) 
        val doNodeVisitor = isCustomNodeVisitor(nodeVisitor)
        val extendedVisitor =
          if (doNodeVisitor)
            nodeVisitor match {
              case e: ExtendedNodeVisitor => Some(e)
              case _ => None
            }
          else None
        // not implicit due to issues #4405 and #4407
        object ordNodeWeight extends Ordering[NodeWeight] {
          def compare(x: NodeWeight,
                      y: NodeWeight) = y._2.compare(x._2)
        }
        val qNodes = new PriorityQueue[NodeWeight]()(ordNodeWeight) += ((this->0L))
  
        def sortedAdjacentsNodes(node: NodeT): Option[PriorityQueue[NodeWeight]] = 
          traversal.filteredDiSuccessors(node, visited, false) match {
            case adj if adj.nonEmpty =>
              Some(adj.
                   foldLeft(new PriorityQueue[NodeWeight]()(ordNodeWeight))(
                     (q,n) => q += ((n, dest(node) +
                                        node.outgoingTo(n).filter(edgeFilter(_)).
                                        min(Edge.WeightOrdering).weight))))
            case _ => None
          }
        def relax(pred: NodeT, succ: NodeT) {
          val cost = dest(pred) + pred.outgoingTo(succ).filter(edgeFilter(_)).
                                  min(Edge.WeightOrdering).weight
          if(!dest.isDefinedAt(succ) || cost < dest(succ)) {
            dest      += (succ->cost)
            mapToPred += (succ->pred)
          }
        }
        var nodeCnt = 0
        var canceled = false
        @tailrec def rec(pq: PriorityQueue[NodeWeight]) {
          if(pq.nonEmpty && (pq.head._1 ne to)) { 
            val nodeWeight = pq.dequeue
            val node = nodeWeight._1
            if (!node.visited) {
              sortedAdjacentsNodes(node) match {
                case Some(ordNodes) =>
                  if (ordNodes.nonEmpty) pq ++= (ordNodes)
                  @tailrec def loop(pq2: PriorityQueue[NodeWeight]) {
                    if (pq2.nonEmpty) {
                      relax(node, pq2.dequeue._1)
                      loop(pq2)
                    }
                  }
                  loop(ordNodes)
                case None =>
              }
              node.visited = true
              if (doNodeVisitor && extendedVisitor.map { v =>
                nodeCnt += 1
                v(node, nodeCnt, 0,
                  new DijkstraInformer[NodeT] {
                    def queueIterator = qNodes.toIterator
                    def costsIterator = dest.toIterator
                  })
              }.getOrElse(nodeVisitor(node)) == Cancel) {
                canceled = true
                return
              }
            }
            rec(pq)
          }
        }
        rec(qNodes) 
        def traverseMapNodes(map: MutableMap[NodeT,NodeT]): Option[Path] = {
          map.get(to).map ( _ =>
            new MinWeightEdgeLazyPath(
                new MapPathTraversable[NodeT](map, to, this),
                edgeFilter)
          ) orElse (
            if(this eq to) Some(Path.zero(to)) else None
          )
        }
        if (canceled) None
        else traverseMapNodes(mapToPred)
      }
    }
    override def findCycle(nodeFilter : (NodeT) => Boolean       = anyNode,
                           edgeFilter : (EdgeT) => Boolean       = anyEdge,
                           maxDepth   :  Int                     = 0,
                           nodeVisitor: (NodeT) => VisitorReturn = noNodeAction,
                           edgeVisitor: (EdgeT) => Unit          = noEdgeAction,
                           ordering   : ElemOrdering             = noOrdering): Option[Cycle] =
    {
      cycle( 
        new Traversal(Successors, nodeFilter, edgeFilter, nodeVisitor, edgeVisitor, ordering).
          depthFirstSearchWGB (this),
        edgeFilter
      )
    }
    final override
    def traverse (direction  : Direction          = Successors,
                  nodeFilter : (NodeT) => Boolean = anyNode,
                  edgeFilter : (EdgeT) => Boolean = anyEdge,
                  breadthFirst:Boolean            = true,
                  maxDepth   :  Int               = 0,
                  ordering   : ElemOrdering       = noOrdering)
                 (nodeVisitor: (NodeT) => VisitorReturn  = noNodeAction,
                  edgeVisitor: (EdgeT) => Unit           = noEdgeAction)
    {
      new Traversal(direction, nodeFilter,  edgeFilter, nodeVisitor, edgeVisitor, ordering).
        apply(this, noNode, breadthFirst, maxDepth)
    }
  }

  class Traversal(direction  : Direction,
                  nodeFilter : (NodeT) => Boolean,
                  edgeFilter : (EdgeT) => Boolean,
                  nodeVisitor: (NodeT) => VisitorReturn,
                  edgeVisitor: (EdgeT) => Unit,
                  ordering   : ElemOrdering)
    extends super.Traversal(direction, nodeFilter, edgeFilter, nodeVisitor, edgeVisitor, ordering)
  {
    final protected val addMethod = direction match {
      case Successors   => Node.addDiSuccessors _
      case Predecessors => Node.addDiPredecessors _
      case AnyConnected => Node.addNeighbors _
    }
    final protected val addFilteredMethod = direction match {
      case Successors   => filteredDiSuccessors _
      case Predecessors => filteredDiPredecessors _
      case AnyConnected => filteredNeighbors _
    }
    final protected val edgesMethod = direction match {
      case Successors   => (n: NodeT) => n.outgoing
      case Predecessors => (n: NodeT) => n.incoming
      case AnyConnected => (n: NodeT) => n.edges
    }
    @transient final protected val doNodeFilter = isCustomNodeFilter(nodeFilter)
    @transient final protected val doEdgeFilter = isCustomEdgeFilter(edgeFilter)
    @transient final protected val doFilter     = doNodeFilter || doEdgeFilter
    @transient final protected val doNodeVisitor= isCustomNodeVisitor(nodeVisitor)
    @transient final protected val doEdgeVisitor= isCustomEdgeVisitor(edgeVisitor)
    @transient final protected val (doNodeSort, nodeOrdering, reverseNodeOrdering,
                                    doEdgeSort, edgeOrdering, reverseEdgeOrdering) =
      ordering match {
        case nO: NodeOrdering => (true,  nO,   nO.reverse,
                                  false, null, null)
        case eO: EdgeOrdering => (false, null, null,
                                  true,  eO,   eO.reverse)
        case _ : NoOrdering   => (false, null, null,
                                  false, null, null)
      }
    protected[collection]
    def filteredDi(direction: Direction,
                   node     :  NodeT,
                   isVisited: (NodeT) => Boolean,
                   reverse  : Boolean): Iterable[NodeT] =
    {
      val edges: Iterable[EdgeT] = {
        val edges = {
          val es = edgesMethod(node)
          if (doEdgeFilter) es filter edgeFilter
          else              es
        }
        if(doEdgeSort) {
          def sorted(ordering: Ordering[EdgeT]) = edges match {
            case a: ArraySet[EdgeT] => a.sorted(ordering)
            case s                  => s.toList.sorted(ordering)
          }
          if(reverse) sorted(reverseEdgeOrdering)
          else        sorted(edgeOrdering)
        } else        edges
      }
      val succ = ArraySet.empty[NodeT](edges.size)
      if (doFilter) {
        def addFilteredNeighbors(edge: EdgeT) {
          addMethod(node, edge,
                   (n: NodeT) => if (nodeFilter(n) && ! isVisited(n))
                                 succ += n)
          if (doEdgeVisitor) edgeVisitor(edge)
        }
        edges foreach addFilteredNeighbors
      } else {
        edges foreach { (e: EdgeT) =>
          addMethod(node, e,
                    (n: NodeT) => if (! isVisited(n)) succ += n)
          if (doEdgeVisitor) edgeVisitor(e)
        }
      }
      if(doNodeSort)
        if(reverse) succ.sorted(reverseNodeOrdering)
        else        succ.sorted(nodeOrdering)
      else          succ
    }
    @inline final override protected[collection]
    def filteredDiSuccessors(node     :  NodeT,
                             isVisited: (NodeT) => Boolean,
                             reverse  : Boolean): Iterable[NodeT] =
      filteredDi(Successors, node, isVisited, reverse)
    @inline final override protected[collection]
    def filteredDiPredecessors(node     :  NodeT,
                               isVisited: (NodeT) => Boolean,
                               reverse  : Boolean): Iterable[NodeT] =
      filteredDi(Predecessors, node, isVisited, reverse)
    @inline final override protected[collection]
    def filteredNeighbors(node     :  NodeT,
                          isVisited: (NodeT) => Boolean,
                          reverse  : Boolean): Iterable[NodeT] =
      filteredDi(AnyConnected, node, isVisited, reverse)

    override def apply(root        : NodeT,
                       pred        : (NodeT) => Boolean = noNode,
                       breadthFirst: Boolean            = true,
                       maxDepth    : Int                = 0): Option[NodeT] =
    {
      if (breadthFirst) breadthFirstSearch(root, pred, maxDepth)
      else              depthFirstSearch  (root, pred, maxDepth)
    }
    override def depthFirstSearch(
        root         : NodeT,
        pred         : (NodeT) => Boolean = noNode,
        maxDepth     : Int                = 0,
        nodeUpVisitor: (NodeT) => Unit    = noNodeUpAction): Option[NodeT] =
      _depthFirstSearch(root, pred, maxDepth, nodeUpVisitor)._1

    final protected[GraphTraversalImpl] def _depthFirstSearch(
        root         : NodeT,
        pred         : (NodeT) => Boolean = noNode,
        maxDepth     : Int                = 0,
        nodeUpVisitor: (NodeT) => Unit    = noNodeUpAction): (Option[NodeT], Stack[(NodeT, Int)]) =
    withHandle() { implicit visitedHandle => 
      val stack: Stack[(NodeT, Int)] = Stack((root, 0))
      val path:  Stack[(NodeT, Int)] = Stack()
      val untilDepth: Int = if (maxDepth > 0) maxDepth else java.lang.Integer.MAX_VALUE
      @inline def isVisited(n: NodeT): Boolean = n.visited  
      val extendedVisitor = nodeVisitor match {
        case e: ExtendedNodeVisitor => Some(e)
        case _ => None
      }
      val doNodeUpVisitor = isCustomNodeUpVisitor(nodeUpVisitor)
      var res: Option[NodeT] = None
      var nodeCnt = 0
      root.visited = true
      @tailrec def loop {
        if(stack.nonEmpty) {
          val (current, depth) = {
            val popped = stack.pop
            val depth = popped._2
            if (depth > 0)
              while (path.head._2 >= depth) {
                if (doNodeUpVisitor) nodeUpVisitor(path.head._1)
                path.pop
              }
            path.push(popped)
            popped
          }
          if (doNodeVisitor && extendedVisitor.map{ v =>
                nodeCnt += 1
                v(current, nodeCnt, depth,
                  new DfsInformer[NodeT] {
                    def stackIterator = stack.toIterator 
                    def pathIterator  = path .toIterator 
                  })
              }.getOrElse(nodeVisitor(current)) == Cancel)
              return
          if (pred(current) && (current ne root)) {
            res = Some(current)
          } else {
            if (depth < untilDepth)
              for (n <- addFilteredMethod(current, isVisited, true)
                        filterNot (isVisited(_))) {
                stack.push((n, depth + 1))
                n.visited = true
              }
            loop
          }
        }
      }
      loop
      if (doNodeUpVisitor) path foreach (e => nodeUpVisitor(e._1))
      (res, path)
    }
    /**
     * Tail-recursive white-gray-black DFS implementation for cycle detection.
     * 
     * @param root start node for the search
     * @param predicate node predicate marking an end condition for the search
     */
    protected[collection]
    def depthFirstSearchWGB(root      :  NodeT,
                            predicate : (NodeT) => Boolean  = noNode,
                            globalState: Array[Handle]= Array.empty[Handle])
        : (Option[NodeT], Stack[CycleStackElem]) =
    {
      withHandles(2, globalState) { handles =>
        implicit val visitedHandle = handles(0)
        val blackHandle = handles(1)
        
        // (node, predecessor, exclude, 0 to 2 multi edges) with last two for undirected
        val stack: Stack[(NodeT, NodeT, Boolean, Iterable[EdgeT])] =
            Stack((root, root, false, Nil))
        // (node, connecting with prev)
        val path = Stack.empty[CycleStackElem]
        val isDiGraph = selfGraph.isDirected
        def isWhite (node: NodeT) = nonVisited(node)
        def isGray  (node: NodeT) = isVisited(node) && ! (node bit(blackHandle))
        def isBlack (node: NodeT) = node bit(blackHandle)
        def setGray (node: NodeT) { node.visited = true }
        def setBlack(node: NodeT) { node.bit_=(true)(blackHandle) } 
  
        def onNodeDown(node: NodeT) { setGray (node) } 
        def onNodeUp  (node: NodeT) { setBlack(node) }
  
        def isVisited (node: NodeT) = node.visited
        def nonVisited(node: NodeT) = ! isVisited(node)
        val extendedVisitor = nodeVisitor match {
          case e: ExtendedNodeVisitor => Some(e)
          case _ => None
        }
        var res: Option[NodeT] = None
        var nodeCnt = 0
        /* pushed allows to track the path.
         * prev   serves the special handling of undirected edges. */
        @tailrec
        def loop(pushed: Boolean) {
          if (res.isEmpty)
            if (stack.isEmpty)
              path foreach (t => setBlack(t._1))
            else {
              val popped = stack.pop
              val exclude: Option[NodeT] =
                if (pushed)
                  if (popped._3) Some(popped._2) else None
                else {
                  while ( path.nonEmpty &&
                         (path.head._1 ne root) &&
                         (path.head._1 ne popped._2)) {
                    val p = path.pop._1
                    if (! isBlack(p))
                      onNodeUp(p) 
                  }
                  Some(path.head._1)
                }
              val current = popped._1
              path.push((current, popped._4))
              if (nonVisited(current)) onNodeDown(current)
              if (doNodeVisitor && extendedVisitor.map{ v =>
                    nodeCnt += 1
                    v(current, nodeCnt, 0,
                      new WgbInformer[NodeT, EdgeT] {
                        def stackIterator = stack.toIterator 
                        def pathIterator  = path .toIterator 
                      })
                  }.getOrElse(nodeVisitor(current)) == Cancel)
                return
              if (predicate(current) && (current ne root))
                res = Some(current)
              else {
                var pushed = false
                for (n <- addFilteredMethod(current, isBlack(_), true)
                          filterNot (isBlack(_))) { 
                  if (isGray(n)) {
                    if (exclude map (_ ne n) getOrElse true)
                      res = Some(n)
                  } else {
                    if (isDiGraph)
                      stack.push((n, current, false, Nil))
                    else {
                      val (excl, multi): (Boolean, Iterable[EdgeT]) = {
                        val conn = current connectionsWith n
                        (conn.size: @switch) match {
                          case 0 => throw new NoSuchElementException
                          case 1 => (true, conn)
                          case _ => (false, conn)
                        }
                      }
                      stack.push((n, current, excl, multi))
                    }
                    pushed = true
                  }
                }
                loop(pushed)
              }
            }
        }
        loop(true)
        (res, path)
      }
    }
    override def breadthFirstSearch(root    : NodeT,
                                    pred    : (NodeT) => Boolean = noNode,
                                    maxDepth: Int                = 0): Option[NodeT] =
    {
      withHandle() { implicit visitedHandle => 
        val untilDepth = if (maxDepth > 0) maxDepth else java.lang.Integer.MAX_VALUE
        var depth = 0
        var nodeCnt = 0
        val q = Queue[(NodeT, Int)](root -> depth)
        val doNodeVisitor = isCustomNodeVisitor(nodeVisitor)
        val extendedVisitor =
          if (doNodeVisitor)
            nodeVisitor match {
              case e: ExtendedNodeVisitor => Some(e)
              case _ => None
            }
          else None
        @inline def visited(n: NodeT) = n.visited  
        @inline def visitAndCanceled(n: NodeT) = {
          n.visited = true
          doNodeVisitor && extendedVisitor.map{ v =>
            nodeCnt += 1
            v(n, nodeCnt, depth,
              new BfsInformer[NodeT] {
                def queueIterator = q.toIterator 
              })
          }.getOrElse(nodeVisitor(n)) == Cancel
        }
        if (visitAndCanceled(root)) return None
        while (q.nonEmpty) { 
          val (prevNode, prevDepth) = q.dequeue
          if (prevDepth < untilDepth) {
            depth = prevDepth + 1
            for (node <- addFilteredMethod(prevNode, visited, false)) { 
              if (visitAndCanceled(node)) return None
              if (pred(node)) return Some(node)
              q enqueue (node -> depth)  
            }
          }
        }
        None
      }
    }
  }
  override def newTraversal(direction  : Direction                = Successors,
                            nodeFilter : (NodeT) => Boolean       = anyNode,
                            edgeFilter : (EdgeT) => Boolean       = anyEdge,
                            nodeVisitor: (NodeT) => VisitorReturn = noNodeAction,
                            edgeVisitor: (EdgeT) => Unit          = noEdgeAction,
                            ordering   : ElemOrdering             = noOrdering) =    
    new Traversal(direction, nodeFilter, edgeFilter, nodeVisitor, edgeVisitor, ordering)

  /** Efficient reverse `foreach` overcoming `ArrayStack`'s deficiency
   *  not to overwrite `reverseIterator`.
   */
  final protected class ReverseStackTraversable[S,T](
        s: Seq[S],
        toT: S => T,
        dropWhile: Option[S => Boolean] = None,
        enclosed: Array[Option[S]] = Array[Option[S]](None, None)
      ) extends Traversable[T] {
    
    @inline def foreach[U](f: T => U): Unit = source foreach (s => f(toT(s)))
    
    @inline override val size: Int = s.size + enclosed.count(_.isDefined)
    @inline override def last: T = enclosed(1) map toT getOrElse toT(s(0))
    def reverse: Traversable[T] = new Abstract.Traversable[T] {
      def foreach[U](f: T => U): Unit = {
        def fT(elem: S): Unit = f(toT(elem))
        def end(i: Int) = enclosed(i) map fT
        end(1)
        s foreach fT
        end(0)
      }
    }
    
    private lazy val upper = dropWhile map { pred =>
      var i = s.size - 1
      while (i >= 0 && pred(s(i)))
        i -= 1
      if (i < 0) 0 else i
    } getOrElse s.size
    
    lazy val source: Traversable[S] = new Abstract.Traversable[S] {
      def foreach[U](f: S => U): Unit = {
        enclosed(0) map f
        var i = upper
        while (i > 0) {
          i -= 1
          f(s(i))
        }
        enclosed(1) map f
      }
    }
  }
  
  /** Enables lazy traversing of a `Map` with `key = source, value = target`.
   */
  final protected class MapPathTraversable[T](map: MutableMap[T,T], to: T, start: T)
      extends Traversable[T] {
    
    private lazy val s: Seq[T] = {
      val stack = Stack.empty[T]
      @tailrec def loop(k: T): Unit = {
        val opt = map.get(k)
        if (opt.isDefined) {
          stack push k
          loop(opt.get)
        }
      }
      loop(to)
      stack push start
      stack
    }
      
    @inline def foreach[U](f: T => U): Unit = s foreach f
  }

  /** Path based on the passed collection of nodes with lazy evaluation of edges.
   */
  protected abstract class LazyPath(val nodes : Traversable[NodeT],
                                          edgeFilter: (EdgeT) => Boolean)
      extends Path {

    def foreach[U](f: GraphParamOut[N,E] => U): Unit = {
      f(nodes.head)
      val edges = this.edges.toIterator
      for (n <- nodes.tail;
           e = edges.next) {
        f(e)
        f(n)
      }
    }
    def startNode = nodes.head
    def endNode   = nodes.last
    def isValid: Boolean = {
      nodes.headOption map { startNode =>
        val edges = this.edges.toIterator
        (nodes.head /: nodes.tail){ (prev: NodeT, n: NodeT) =>
          if (edges.hasNext) {
            val e = edges.next
            if (! e.matches((x: NodeT) => x eq prev,
                            (x: NodeT) => x eq n   )) return false
            n
          } else return false
        }
        true
      } getOrElse false
    }

    override def equals(other: Any) = other match {
      case that: GraphTraversalImpl[N,E]#Path => 
        (this eq that) ||
        that.toArray[GraphParamOut[N,E]].sameElements(toArray[GraphParamOut[N,E]])
      case _ => false
    }
    override def hashCode = nodes.## + 27 * edges.##  
  }
  
  /** `LazyPath` with deferred edges selection.
   */
  protected abstract class SimpleLazyPath(override val nodes : Traversable[NodeT],
                                          edgeFilter: (EdgeT) => Boolean)
      extends LazyPath(nodes, edgeFilter) {
    
    final lazy val edges = {
      val buf = new ArrayBuffer[EdgeT](nodes.size)
      (nodes.head /: nodes.tail){ (prev: NodeT, n: NodeT) =>
        buf += selectEdge(prev, n)
        n
      }
      buf
    }

    protected def selectEdge(from: NodeT, to: NodeT): EdgeT
  }

  /** `LazyPath` where edges are selected by taking the first one fitting.
   */
  protected class AnyEdgeLazyPath(override val nodes : Traversable[NodeT],
                                  edgeFilter: (EdgeT) => Boolean)
      extends SimpleLazyPath(nodes, edgeFilter) {
    
    final protected def selectEdge(from: NodeT, to: NodeT): EdgeT =
      if (isCustomEdgeFilter(edgeFilter))
        (from outgoingTo to find edgeFilter).get
      else
        (from findOutgoingTo to).get
  }

  /** `LazyPath` with edges selected by minimal weight.
   */
  protected class MinWeightEdgeLazyPath(override val nodes : Traversable[NodeT],
                                        edgeFilter: (EdgeT) => Boolean)
      extends SimpleLazyPath(nodes, edgeFilter) {
    
    final def selectEdge (from: NodeT, to: NodeT): EdgeT =
      if (isCustomEdgeFilter(edgeFilter))
        from outgoingTo to filter edgeFilter min(Edge.WeightOrdering)
      else
        from outgoingTo to min(Edge.WeightOrdering)
  }

  /** `LazyPath` with edge selection such that there exists no duplicate edge in the path.
   */
  protected class MultiEdgeLazyPath(
      override val nodes : ReverseStackTraversable[CycleStackElem, NodeT],
      edgeFilter: (EdgeT) => Boolean)
      extends LazyPath(nodes, edgeFilter) {
    
    import mutable.EqSet, mutable.EqSet.EqSetMethods
    final protected val multi = EqSet[EdgeT](graphSize / 2) 
        
    final lazy val edges = {
      val buf = new ArrayBuffer[EdgeT](nodes.size)
      (nodes.head /: nodes.source.tail){ (prev: NodeT, elem: CycleStackElem) =>
        val (n, conn) = elem
        val edge = (conn.size: @switch) match {
          case 0 => n.edges.find (e => ! multi.contains(e) &&
                                       edgeFilter(e) &&
                                       e.hasTarget((x: NodeT) => x eq prev)).get          
          case 1 => conn.head
          case _ => conn.find (e => ! multi.contains(e) &&
                                    edgeFilter(e) &&
                                    e.hasSource((x: NodeT) => x eq n)).get
        }
        buf += edge
        multi += edge
        n
      }
      multi.clear
      buf
    }
  }
  
  protected trait CycleMethods extends Cycle {
    
    final def sameAs(that: GraphTraversal[N,E]#Cycle) =
      this == that || ( that match {
        case that: GraphTraversalImpl[N,E]#Cycle =>
          this.size == that.size && {
            val thisList = to[List]
            val idx = thisList.indexOf(that.head)
            if (idx >= 0) {
              val thisDoubled = thisList ++ (thisList.tail)
              val thatList = that.to[List]
              (thisDoubled startsWith (thatList        , idx)) ||
              (thisDoubled startsWith (thatList.reverse, idx))
            }
            else false
          }
        case _ => false
      })
  }
  
  protected class AnyEdgeLazyCycle(override val nodes : Traversable[NodeT],
      edgeFilter: (EdgeT) => Boolean)
      extends AnyEdgeLazyPath(nodes, edgeFilter)
         with CycleMethods

  protected class MultiEdgeLazyCycle(
      override val nodes : ReverseStackTraversable[CycleStackElem, NodeT],
      edgeFilter: (EdgeT) => Boolean)
      extends MultiEdgeLazyPath(nodes, edgeFilter)
         with CycleMethods    
}
object GraphTraversalImpl {
  import GraphTraversal._

  /** Extended node visitor informer for depth first searches. 
   */
  trait DfsInformer[N] extends NodeInformer {
    import DfsInformer._
    def stackIterator: DfsStack[N]
    def pathIterator:  DfsPath [N]
  }
  object DfsInformer {
    type DfsStack[N] = Iterator[(N, Int)]
    type DfsPath [N] = Iterator[(N, Int)]
    def unapply[N](inf: DfsInformer[N]): Option[(DfsStack[N], DfsPath[N])] =
      Some(inf.stackIterator, inf.pathIterator)
  }
  /** Extended node visitor informer for cycle detecting. 
   *  This informer always returns `0` for `depth`.
   */
  trait WgbInformer[N,E] extends NodeInformer {
    import WgbInformer._
    def stackIterator: WgbStack[N,E]
    def pathIterator:  WgbPath [N,E]
  }
  object WgbInformer {
    type WgbStack[N,E] = Iterator[(N, N, Boolean, Iterable[E])]
    type WgbPath [N,E] = Iterator[(N, Iterable[E])]
    def unapply[N,E](inf: WgbInformer[N,E]): Option[(WgbStack[N,E], WgbPath[N,E])] =
      Some(inf.stackIterator, inf.pathIterator)
  }
  /** Extended node visitor informer for best first searches. 
   */
  trait BfsInformer[N] extends NodeInformer {
    import BfsInformer._
    def queueIterator: BfsQueue[N]
  }
  object BfsInformer {
    type BfsQueue[N] = Iterator[(N, Int)]
    def unapply[N](inf: BfsInformer[N]): Option[BfsQueue[N]] =
      Some(inf.queueIterator)
  }
  /** Extended node visitor informer for calculating shortest paths. 
   *  This informer always returns `0` for `depth`.
   */
  trait DijkstraInformer[N] extends NodeInformer {
    import DijkstraInformer._
    def queueIterator: DijkstraQueue[N]
    def costsIterator: DijkstraCosts[N]
  }
  object DijkstraInformer {
    type DijkstraQueue[N] = Iterator[(N, Long)]
    type DijkstraCosts[N] = Iterator[(N, Long)]
    def unapply[N](inf: DijkstraInformer[N])
        : Option[(DijkstraQueue[N], DijkstraCosts[N])] =
      Some(inf.queueIterator, inf.costsIterator)
  }
} 