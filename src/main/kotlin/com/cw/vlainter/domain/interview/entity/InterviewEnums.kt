package com.cw.vlainter.domain.interview.entity

enum class QuestionSetOwnerType {
    USER, ADMIN
}

enum class QuestionSetVisibility {
    PRIVATE, GLOBAL
}

enum class QuestionSetStatus {
    ACTIVE, ARCHIVED
}

enum class EmbeddingStatus {
    NOT_EMBEDDED, QUEUED, PROCESSING, READY, FAILED
}

enum class QuestionDifficulty {
    EASY, MEDIUM, HARD
}

enum class QuestionSourceTag {
    SYSTEM, USER
}

enum class InterviewMode {
    DOC, TECH, MIXED
}

enum class InterviewStatus {
    IN_PROGRESS, FINISHING, DONE, FAILED, CANCELLED
}

enum class RevealPolicy {
    END_ONLY, PER_TURN
}

enum class TurnSourceTag {
    SYSTEM, USER, DOC_RAG
}

enum class TurnEvaluationStatus {
    PENDING, DONE, FAILED
}

enum class InterviewQuestionKind {
    TECH, DOCUMENT
}

enum class DocumentIngestionStatus {
    QUEUED, PROCESSING, READY, FAILED, CANCELLED
}
