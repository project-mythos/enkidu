package Enkidu

import java.net.{InetSocketAddress}

import com.twitter.util.{Future}



case class Node(
  id: Long,
  host: String,
  port: Int
) {


  def toSocketAddress = Node.toSocketAddress(this)
  override def toString = Node.toString(this)
}


object Node {


  val host_port_fmt = "%s:%d"
  val addr_fmt = "%d@%s:%d"

  
  def toString(addr: Node) = {
    addr_fmt.format(addr.id, addr.host, addr.port)
  }

  def apply(host: String, port: Int): Node = {
    val f = host_port_fmt.format(host, port)
    val id = Enki.KeyHasher.FNV1A_64.hashKey(f.getBytes)
    Node(id, host, port)
  }

  def fromString(s: String) = {
    val id :: tl = s.split("@").toList

    val toks = tl(0).split(":").toList
    Node(id.toLong, toks(0), toks(1).toInt)
  }


  def toSocketAddress(addr: Node) = {
    new InetSocketAddress(addr.host, addr.port)
  }

  


  def fromSocketAddress(sockAddr: InetSocketAddress) = {
    val host = sockAddr.getHostString()
    val port = sockAddr.getPort()
    Node(host, port)
  }

}
