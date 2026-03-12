package com.cw.vlainter.domain.interview.entity

enum class QuestionSetOwnerType {
    USER, ADMIN
}

enum class QuestionSetVisibility {
    PRIVATE, GLOBAL
}

@Suppress("unused")
enum class QuestionSetStatus {
    ACTIVE, ARCHIVED
}

enum class QuestionDifficulty {
    EASY, MEDIUM, HARD
}

enum class QuestionSourceTag {
    SYSTEM, USER
}

enum class InterviewLanguage {
    KO, EN
}

enum class InterviewMode {
    DOC, TECH, MIXED, QUESTION_SET_PRACTICE
}

@Suppress("unused")
enum class InterviewStatus {
    IN_PROGRESS, FINISHING, DONE, FAILED, CANCELLED
}

enum class RevealPolicy {
    END_ONLY, PER_TURN
}

enum class TurnSourceTag {
    SYSTEM, USER, DOC_RAG, INTRO
}

@Suppress("unused")
enum class TurnEvaluationStatus {
    PENDING, DONE, FAILED
}

enum class InterviewQuestionKind {
    TECH, DOCUMENT, INTRO
}

enum class DocumentIngestionStatus {
    QUEUED, PROCESSING, READY, FAILED, CANCELLED
}
