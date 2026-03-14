@file:Suppress("NonAsciiCharacters")

package com.cw.vlainter.domain.site.service

import com.cw.vlainter.domain.site.dto.CreatePatchNoteRequest
import com.cw.vlainter.domain.site.dto.UpdatePatchNoteRequest
import com.cw.vlainter.domain.site.entity.PatchNote
import com.cw.vlainter.domain.site.repository.PatchNoteRepository
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.global.security.AuthPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class PatchNoteServiceTests {

    @Mock
    private lateinit var patchNoteRepository: PatchNoteRepository

    @Test
    fun `공개 패치노트는 published 항목만 반환한다`() {
        given(patchNoteRepository.findAllByIsPublishedTrueOrderBySortOrderAscCreatedAtDesc())
            .willReturn(
                listOf(
                    PatchNote(id = 1L, title = "Landing Refresh", body = "body", sortOrder = 0, isPublished = true),
                    PatchNote(id = 2L, title = "Security", body = "body2", sortOrder = 1, isPublished = true)
                )
            )

        val result = service().getPublishedPatchNotes()

        assertThat(result).hasSize(2)
        assertThat(result.map { it.title }).containsExactly("Landing Refresh", "Security")
    }

    @Test
    fun `관리자는 패치노트를 생성할 수 있다`() {
        given(patchNoteRepository.save(anyNonNull()))
            .willAnswer { invocation ->
                val note = invocation.getArgument<PatchNote>(0)
                PatchNote(
                    id = 10L,
                    title = note.title,
                    body = note.body,
                    sortOrder = note.sortOrder,
                    isPublished = note.isPublished
                )
            }

        val result = service().createPatchNote(
            adminPrincipal(),
            CreatePatchNoteRequest(title = "  Landing Refresh  ", body = "  body  ", sortOrder = 2, isPublished = false)
        )

        assertThat(result.patchNoteId).isEqualTo(10L)
        assertThat(result.title).isEqualTo("Landing Refresh")
        assertThat(result.body).isEqualTo("body")
        assertThat(result.sortOrder).isEqualTo(2)
        assertThat(result.isPublished).isFalse()
    }

    @Test
    fun `관리자는 패치노트를 수정할 수 있다`() {
        val existing = PatchNote(
            id = 3L,
            title = "Old",
            body = "Old body",
            sortOrder = 0,
            isPublished = true
        )
        given(patchNoteRepository.findById(3L)).willReturn(Optional.of(existing))
        given(patchNoteRepository.save(existing)).willReturn(existing)

        val result = service().updatePatchNote(
            adminPrincipal(),
            3L,
            UpdatePatchNoteRequest(title = " New ", body = " New body ", sortOrder = 5, isPublished = false)
        )

        assertThat(result.title).isEqualTo("New")
        assertThat(result.body).isEqualTo("New body")
        assertThat(result.sortOrder).isEqualTo(5)
        assertThat(result.isPublished).isFalse()
    }

    @Test
    fun `관리자가 아니면 패치노트를 삭제할 수 없다`() {
        val exception = assertThrows(ResponseStatusException::class.java) {
            service().deletePatchNote(userPrincipal(), 1L)
        }

        assertThat(exception.statusCode.value()).isEqualTo(403)
    }

    @Test
    fun `관리자는 패치노트를 삭제할 수 있다`() {
        val existing = PatchNote(
            id = 4L,
            title = "Delete me",
            body = "body",
            sortOrder = 0,
            isPublished = true
        )
        given(patchNoteRepository.findById(4L)).willReturn(Optional.of(existing))

        service().deletePatchNote(adminPrincipal(), 4L)

        then(patchNoteRepository).should().delete(existing)
    }

    @Test
    fun `패치노트 reorder 요청에 중복 ID가 있으면 거부한다`() {
        val exception = assertThrows(ResponseStatusException::class.java) {
            service().reorderPatchNotes(
                adminPrincipal(),
                com.cw.vlainter.domain.site.dto.ReorderPatchNotesRequest(listOf(1L, 1L, 2L))
            )
        }

        assertThat(exception.statusCode.value()).isEqualTo(400)
        assertThat(exception.reason).isEqualTo("중복된 패치노트 ID가 있습니다.")
    }

    private fun service(): PatchNoteService = PatchNoteService(patchNoteRepository)

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T {
        ArgumentMatchers.any<T>()
        return null as T
    }

    private fun adminPrincipal() = AuthPrincipal(1L, "admin@vlainter.com", "S", UserRole.ADMIN)

    private fun userPrincipal() = AuthPrincipal(2L, "user@vlainter.com", "S", UserRole.USER)
}
