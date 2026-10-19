package app.televault.people

/** A changed answer or pair starts again; only two consecutive independent reviews commit. */
internal data class FaceReviewVote(val target: String = "", val same: Boolean = false, val count: Int = 0) {
    fun next(target: String, same: Boolean) = FaceReviewVote(target, same, if (this.target == target && this.same == same && count == 1) 2 else 1)
    val confirmed get() = count == 2
}
