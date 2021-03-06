package scorex.crypto.authds.merkle

import scorex.crypto.hash.CryptographicHash

import scala.annotation.tailrec
import scala.collection.mutable

case class MerkleTree(topNode: InternalNode,
                      elementsHashIndexes: Map[mutable.WrappedArray.ofByte, Int]) {

  lazy val rootHash: Array[Byte] = topNode.hash
  lazy val length: Int = elementsHashIndexes.size

  def proofByElement(element: Leaf): Option[MerkleProof] = proofByElementHash(element.hash)

  def proofByElementHash(hash: Array[Byte]): Option[MerkleProof] = {
    elementsHashIndexes.get(new mutable.WrappedArray.ofByte(hash)).flatMap(i => proofByIndex(i))
  }

  def proofByIndex(index: Int): Option[MerkleProof] = if (index >= 0 && index < length) {
    def loop(node: Node, i: Int, curLength: Int, acc: Seq[(Array[Byte], MerkleProof.Side)])
    : Option[(Leaf, Seq[(Array[Byte], MerkleProof.Side)])] = {
      node match {
        case n: InternalNode if i < curLength / 2 =>
          loop(n.left, i, curLength / 2, acc :+ (n.right.hash, MerkleProof.LeftSide))
        case n: InternalNode if i < curLength =>
          loop(n.right, i - curLength / 2, curLength / 2, acc :+ (n.left.hash, MerkleProof.RightSide))
        case n: Leaf =>
          Some((n, acc.reverse))
        case _ =>
          None
      }
    }

    val leafWithProofs = loop(topNode, index, lengthWithEmptyLeafs, Seq())
    leafWithProofs.map(lp => MerkleProof(lp._1.data, lp._2)(lp._1.hf))
  } else {
    None
  }

  lazy val lengthWithEmptyLeafs: Int = {
    def log2(x: Double): Double = math.log(x) / math.log(2)

    Math.max(math.pow(2, math.ceil(log2(length))).toInt, 2)
  }

  //Debug only
  override lazy val toString: String = {
    def loop(nodes: Seq[Node], level: Int, acc: String): String = {
      if (nodes.nonEmpty) {
        val thisLevStr = s"Level $level: " + nodes.map(_.toString).mkString(",") + "\n"
        val nextLevNodes = nodes.flatMap {
          case i: InternalNode => Seq(i.left, i.right)
          case _ => Seq()
        }
        loop(nextLevNodes, level + 1, acc + thisLevStr)
      } else {
        acc
      }
    }

    loop(Seq(topNode), 0, "")
  }
}

object MerkleTree {
  val LeafPrefix: Byte = 0: Byte
  val InternalNodePrefix: Byte = 1: Byte

  def apply(payload: Seq[Array[Byte]])
           (implicit hf: CryptographicHash): MerkleTree = {
    val leafs = payload.map(d => Leaf(d))
    val elementsIndex: Map[mutable.WrappedArray.ofByte, Int] = leafs.indices.map { i =>
      (new mutable.WrappedArray.ofByte(leafs(i).hash), i)
    }.toMap
    val topNode = calcTopNode(leafs)

    MerkleTree(topNode, elementsIndex)
  }

  @tailrec
  def calcTopNode(nodes: Seq[Node])(implicit hf: CryptographicHash): InternalNode = {
    val nextNodes = nodes.grouped(2).map(lr => InternalNode(lr.head, if (lr.length == 2) lr.last else EmptyNode)).toSeq
    if (nextNodes.length == 1) nextNodes.head else calcTopNode(nextNodes)
  }
}
