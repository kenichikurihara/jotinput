package com.example.myasr.shared.domain

/**
 * Shared Kernel: DDD における「Judgement（ドメイン上の意思決定・判定）」の基底契約。
 *
 * UI/Android Framework（InputMethodService, Activity 等）から純粋なドメイン判定ロジックを分離し、
 * 入力 [I] に対して決定論的かつ単体テスト可能な判定結果 [O] を返す責務を担う。
 */
fun interface Judgement<in I, out O> {
    fun judge(input: I): O
}

/**
 * 判定結果と、その判定に至ったドメイン上の理由（監査・デバッグ・テスト検証用）を保持する値オブジェクト。
 */
data class JudgementResult<out T>(
    val decision: T,
    val reason: String
)

/**
 * DDD Specification パターン: 合成可能な真偽値判定ルール。
 */
fun interface Specification<in T> {
    fun isSatisfiedBy(candidate: T): Boolean

    infix fun and(other: Specification<T>): Specification<T> = Specification { candidate ->
        this.isSatisfiedBy(candidate) && other.isSatisfiedBy(candidate)
    }

    infix fun or(other: Specification<T>): Specification<T> = Specification { candidate ->
        this.isSatisfiedBy(candidate) || other.isSatisfiedBy(candidate)
    }

    operator fun not(): Specification<T> = Specification { candidate ->
        !this.isSatisfiedBy(candidate)
    }
}
