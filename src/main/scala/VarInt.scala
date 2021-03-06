package Enkidu
import io.netty.buffer.{ByteBuf, Unpooled}
import com.twitter.io._








object VarInt  {


  def encodeInt(v: Int, dest: ByteBuf): Unit = {
    var x = v

    while((x & 0xFFFFF80) != 0L) {
      dest.writeByte(((x & 0x7F) | 0x80).toByte)
      x >>>= 7
    }

    dest.writeByte((x & 0x7F).toByte)

  }

  def decodeInt(src: ByteBuf): Int = {
    var i = 0
    var v = 0
    var read = 0

    do {
      read = src.readByte
      v |= (read & 0x7F) << i
      i += 7
      require(i <= 35)
    } while((read & 0x80) != 0)

    v
  }



  def encodeLong(v: Long, dest: ByteBuf): Unit = {
    var x = v

    while((x & 0xFFFFFFFFFFFFFF80L) != 0L) {
      dest.writeByte(((x & 0x7F) | 0x80).toByte)
      x >>>= 7
    }

    dest.writeByte((x & 0x7F).toByte)
  }


  def decodeLong(src: ByteBuf): Long = {
    var i = 0
    var v = 0L
    var read = 0L

    do {
      read = src.readByte
      v |= (read & 0x7F) << i
      i += 7
      require(i <= 70)
    } while((read & 0x80L) != 0)
      v
  }



}

