package scorex.crypto.hash

object SIMD256 extends FRHash32 {
  override protected def hf: fr.cryptohash.Digest = new fr.cryptohash.SIMD256
}