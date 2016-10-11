package scorex.crypto.authds.treap

import scorex.crypto.authds._
import scorex.crypto.hash.{Blake2b256Unsafe, ThreadUnsafeHash}
import scorex.utils.ByteArray

import scala.util.Success

/**
  * Authenticated data structure, representing both treap and binary tree, depending on level selection function
  */
class Treap[HF <: ThreadUnsafeHash](rootOpt: Option[Leaf] = None)
                                   (implicit hf: HF = new Blake2b256Unsafe, lf: LevelFunction = Level.treapLevel)
  extends TwoPartyDictionary[TreapKey, TreapValue] {

  var topNode: ProverNodes = rootOpt.getOrElse(Leaf(NegativeInfinity._1, NegativeInfinity._2, PositiveInfinity._1))

  def rootHash(): Label = topNode.label

  def modify(key: TreapKey, updateFunction: UpdateFunction): TreapModifyProof = {
    require(ByteArray.compare(key, NegativeInfinity._1) > 0)
    require(ByteArray.compare(key, PositiveInfinity._1) < 0)

    val proofStream = new scala.collection.mutable.Queue[WTProofElement]

    // found tells us if x has been already found above r in the tree
    // returns the new root
    // and an indicator whether tree has been modified at r or below
    def modifyHelper(rNode: ProverNodes, foundAbove: Boolean): (ProverNodes, Boolean) = {
      rNode match {
        case r: Leaf =>
          if (foundAbove) {
            // we already know it's in the tree, so it must be at the current leaf
            proofStream.enqueue(ProofDirection(LeafFound))
            proofStream.enqueue(ProofNextLeafKey(r.nextLeafKey))
            proofStream.enqueue(ProofValue(r.value))
            updateFunction(Some(r.value)) match {
              case Success(v) =>
                r.value = v
                (r, true)
              case _ => (r, false)
            }
          } else {
            // x > r.key
            proofStream.enqueue(ProofDirection(LeafNotFound))
            proofStream.enqueue(ProofKey(r.key))
            proofStream.enqueue(ProofNextLeafKey(r.nextLeafKey))
            proofStream.enqueue(ProofValue(r.value))
            updateFunction(None) match {
              case Success(v) =>
                val newLeaf = new Leaf(key, v, r.nextLeafKey)
                r.nextLeafKey = key
                (ProverNode(key, r, newLeaf), true)
              case _ => (r, false)
            }
          }
        case r: ProverNode =>
          // First figure out the direction in which we need to go
          val (nextStepIsLeft, found) = if (foundAbove) {
            // if it's already been found above, you always go left until leaf
            (true, true)
          } else {
            ByteArray.compare(key, r.key) match {
              case 0 => // found in the tree -- go one step right, then left to the leaf
                (false, true)
              case o if o < 0 => // going left
                (true, false)
              case _ => // going right
                (false, false)
            }
          }
          // Now go recursively in the direction we just figured out
          // Get a new node
          // See if the new node needs to be swapped with r because its level > r.level (if it's left)
          // or its level >= r.level (if it's right)
          if (nextStepIsLeft) {
            proofStream.enqueue(ProofDirection(GoingLeft))
            proofStream.enqueue(ProofRightLabel(r.rightLabel))
            proofStream.enqueue(ProofLevel(r.level))

            var (newLeftM: ProverNodes, changeHappened: Boolean) = modifyHelper(r.left, found)

            if (changeHappened) {
              newLeftM match {
                case newLeft: ProverNode if newLeft.level >= r.level =>
                  // We need to rotate r with newLeft
                  r.left = newLeft.right
                  newLeft.right = r
                  (newLeft, true)
                case newLeft =>
                  // Attach the newLeft because its level is smaller than our level
                  r.left = newLeft
                  (r, true)
              }
            } else {
              // no change happened
              (r, false)
            }
          } else {
            // next step is to the right
            proofStream.enqueue(ProofDirection(GoingRight))
            proofStream.enqueue(ProofLeftLabel(r.leftLabel))
            proofStream.enqueue(ProofLevel(r.level))

            var (newRightM: ProverNodes, changeHappened: Boolean) = modifyHelper(r.right, found)

            if (changeHappened) {
              // This is symmetric to the left case, except of >= replaced with > in the
              // level comparison
              newRightM match {
                case newRight: ProverNode if newRight.level > r.level =>
                  // We need to rotate r with newRight
                  r.right = newRight.left
                  newRight.left = r
                  (newRight, true)
                case newRight =>
                  // Attach the newRight because its level is smaller than or equal to our level
                  r.right = newRight
                  (r, true)
              }
            } else {
              // no change happened
              (r, false)
            }
          }
      }

    }

    var (newTopNode: ProverNodes, changeHappened: Boolean) = modifyHelper(topNode, foundAbove = false)
    topNode = newTopNode
    TreapModifyProof(key, proofStream)
  }

}
