package Enkidu.Loadbalancer

import Enkidu.{Node, ConnectionManager, Flow}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import com.twitter.util.{Var, Updatable, Future, Witness}
import Enki.KeyHasher.{FNV1A_64, MURMUR3}
import Enki.CHash
import scala.util.Random

case class LoadedNode(node: Node) {

  private val counter = new AtomicLong(0L)

  def load() = counter.get
  def incr() = counter.incrementAndGet()
  def decr() = counter.decrementAndGet()

  def toNode: Node = node 

}


trait Distributor {
  def pick(vector: Vector[LoadedNode] ): LoadedNode


  def use[T](vector: Vector[LoadedNode])(fn: LoadedNode => Future[T] ): Future[T] = {
    val n = pick(vector)
    n.incr
    fn(n) ensure n.decr
  }


}



trait KeyDistributor {
  def pick(key: Array[Byte], v: Vector[LoadedNode]): LoadedNode

  def use[T](key: Array[Byte], v: Vector[LoadedNode])(f: LoadedNode => Future[T]): Future[T] = {
    val n = pick(key, v)
    n.incr
    f(n) ensure n.decr 
  }


}




object P2C extends Distributor {

  def pick(nodes: Vector[LoadedNode]) = {

    val a = Random.nextInt(nodes.size) 
    var b = Random.nextInt(nodes.size - 1) 

    if (b >= a) {b = b + 1}

    val (c1, c2) = (nodes(a), nodes(b) )
    if (c1.load <= c2.load) c1
    else c2
  }

}





object P2CPKG extends KeyDistributor {
 
  val hasher = FNV1A_64
  val hasher1 = MURMUR3

  def pick(key: Array[Byte], nodes: Vector[LoadedNode]): LoadedNode = {
    val c1 = nodes( hasher.hashKey(key).toInt % nodes.length )
    val c2 = nodes( hasher1.hashKey(key).toInt % nodes.length )


    if (c1.load <= c2.load) c1
    else c2
  }

}


class CHashLeastLoaded(factor: Int) extends KeyDistributor {

  val hasher = FNV1A_64
  val S = CHash.Sharder

  def pick(key: Array[Byte], nodes: Vector[LoadedNode]) = {
    val key1 = hasher.hashKey(key)
    val shards = S.lookup(nodes, key1, factor)
    shards.minBy(x => x.load)
  }

}





class ServerList(Dest: Var[Set[Node]]) {
  private var servers = Vector[LoadedNode]()

  val membershipWitness = new Witness[Set[Node]] {
    def notify(nodes: Set[Node]) = { update(nodes) }
  }


  def endpoints(): Vector[LoadedNode] = servers

  def update(nodes: Set[Node]) = {

    val e1 = servers.map {ln => ln.node} toSet

    val toRemove = e1 diff nodes

    val toAdd = nodes diff e1 map {x => LoadedNode(x) } toVector

    val v2 = servers filter {x =>
      toRemove.contains( x.node ) != true
    }


    val v3 = (v2 ++ toAdd).sortWith(_.node.id < _.node.id)

    synchronized{ servers = v3 }
    //servers = v3
  }

}




object ServerList {
  type Endpoints = Var[Node] with Updatable[Node]
}





class LB[In, Out](
  CM: ConnectionManager[In, Out],
  D: Distributor,
  SL: ServerList
) {

  def use[T](f: Flow[In, Out] => Future[T]) = {

    D.use(SL.endpoints) {ln =>
      CM.connect(ln.toNode)(f)
    }

  }

}



class KeyBasedLB[In, Out](
  CM: ConnectionManager[In, Out],
  KD: KeyDistributor,
  SL: ServerList
) {


  def use[T](key: Array[Byte], f: Flow[In, Out] => Future[T]) = {

    KD.use(key, SL.endpoints) { ln =>
      CM.connect(ln.toNode)(f)
    }

  }

}