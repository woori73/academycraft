package com.rea.academy.agent.tools;

import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentTool;
import com.rea.academy.agent.AgentUtils;
import com.rea.academy.domain.notice.ReaNotice;
import com.rea.academy.domain.notice.ReaNoticeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공지 초안 작성 도구 (쓰기).
 * 입력: 대상(필수: "ALL" 또는 반 ID), 제목(필수), 내용(필수).
 *
 * 중요: 일반 공지 작성(NoticeService.create)과 달리 학부모 알림을 발송하지 않는다.
 * 발송은 별도 단계(공지 발송)에서 담당한다. 제목에 "[초안]" 접두어를 붙여 저장한다.
 */
@Component
@RequiredArgsConstructor
public class CreateNoticeDraftTool implements AgentTool {

    private static final String DRAFT_PREFIX = "[초안] ";

    private final ReaNoticeRepository noticeRepository;

    @Override
    public String getName() {
        return "createNoticeDraft";
    }

    @Override
    public String getDescription() {
        return "공지 초안을 작성한다. 학부모 알림은 발송하지 않는다. (쓰기: 승인 필요)";
    }

    @Override
    public Map<String, String> getInputSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("target", "대상 (필수: \"ALL\"=전체, 또는 반 ID)");
        schema.put("title", "공지 제목 (필수)");
        schema.put("content", "공지 내용 (필수)");
        return schema;
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, AgentContext ctx) {
        String targetRaw = AgentUtils.requiredString(input, "target", "대상");
        String title = AgentUtils.requiredString(input, "title", "제목");
        String content = AgentUtils.requiredString(input, "content", "내용");

        Long classId = null;
        if (!"ALL".equalsIgnoreCase(targetRaw)) {
            classId = AgentUtils.toLong(targetRaw, "대상(ALL 또는 반 ID)");
        }

        ReaNotice notice = ReaNotice.builder()
                .academyId(ctx.academyId())
                .classId(classId)
                .title(DRAFT_PREFIX + title)
                .content(content)
                .authorId(ctx.principalType() == com.rea.academy.security.AuthContext.PrincipalType.MEMBER
                        ? ctx.principalId() : null)
                .authorName(ctx.principalName())
                .build();
        ReaNotice saved = noticeRepository.save(notice);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("noticeId", saved.getId());
        result.put("title", saved.getTitle());
        result.put("target", classId == null ? "ALL" : String.valueOf(classId));
        result.put("draft", true);
        return result;
    }
}
