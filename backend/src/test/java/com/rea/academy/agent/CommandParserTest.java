package com.rea.academy.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CommandParser: 자연어 명령 → 도구 매핑 테스트 (순수 단위, Spring 불필요).
 */
class CommandParserTest {

    private final CommandParser parser = new CommandParser();

    @ParameterizedTest
    @CsvSource({
            "학생 등록 김철수, registerStudent",
            "김철수 학생 등록, registerStudent",
            "학생 3을 2반에 배정해줘, assignStudentToClass",
            "오늘 결석 학생 알려줘, getAbsentStudents",
            "어제 결석자 조회, getAbsentStudents",
            "2026-10-12 결석 학생, getAbsentStudents",
            "숙제 미제출 학생 확인, getHomeworkIncomplete",
            "3반 숙제 미완료자, getHomeworkIncomplete",
            "공지 작성해줘, createNoticeDraft",
            "전체 공지 초안 작성, createNoticeDraft",
            "성적 분석해줘, analyzeGradeChanges",
            "학생 성적 변화 추이, analyzeGradeChanges",
            "운영 보고서 만들어줘, generateOperationReport",
            "최근 7일 현황 보고서, generateOperationReport",
    })
    @DisplayName("명령 → 도구 매핑")
    void parse_mapsCommandToTool(String command, String expectedTool) {
        CommandParser.ParseResult result = parser.parse(command);

        assertThat(result.ok()).isTrue();
        assertThat(result.toolName()).isEqualTo(expectedTool);
    }

    @Test
    @DisplayName("학생 등록: 이름 추출")
    void parse_registerStudent_extractsName() {
        CommandParser.ParseResult result = parser.parse("학생 등록 김철수");

        assertThat(result.ok()).isTrue();
        assertThat(result.params()).containsEntry("name", "김철수");
    }

    @Test
    @DisplayName("결석 조회: 어제 추출")
    void parse_absent_extractsDate() {
        CommandParser.ParseResult result = parser.parse("어제 결석 학생");

        assertThat(result.ok()).isTrue();
        assertThat(result.params()).containsEntry("date", "어제");
    }

    @Test
    @DisplayName("결석 조회: yyyy-MM-dd 추출")
    void parse_absent_extractsIsoDate() {
        CommandParser.ParseResult result = parser.parse("2026-10-12 결석 학생");

        assertThat(result.ok()).isTrue();
        assertThat(result.params()).containsEntry("date", "2026-10-12");
    }

    @Test
    @DisplayName("반 배정: 학생ID·반ID 추출")
    void parse_assign_extractsIds() {
        CommandParser.ParseResult result = parser.parse("학생 3을 2반에 배정해줘");

        assertThat(result.ok()).isTrue();
        assertThat(result.params()).containsEntry("studentId", 3L);
        assertThat(result.params()).containsEntry("classId", 2L);
    }

    @Test
    @DisplayName("숙제 미제출: 반ID 추출")
    void parse_homework_extractsClassId() {
        CommandParser.ParseResult result = parser.parse("3반 숙제 미제출 학생");

        assertThat(result.ok()).isTrue();
        assertThat(result.params()).containsEntry("classId", 3L);
    }

    @Test
    @DisplayName("성적 분석: 기간 추출")
    void parse_grade_extractsDateRange() {
        CommandParser.ParseResult result = parser.parse("학생 성적 분석 2026-01-01 ~ 2026-06-30");

        assertThat(result.ok()).isTrue();
        assertThat(result.params()).containsEntry("startDate", "2026-01-01");
        assertThat(result.params()).containsEntry("endDate", "2026-06-30");
    }

    @Test
    @DisplayName("보고서: 최근 N일 추출")
    void parse_report_extractsRecentDays() {
        CommandParser.ParseResult result = parser.parse("최근 7일 운영 보고서");

        assertThat(result.ok()).isTrue();
        assertThat(result.params()).containsKey("startDate");
        assertThat(result.params()).containsKey("endDate");
    }

    @Test
    @DisplayName("이해할 수 없는 명령: 실패 + 후보 도구 제안")
    void parse_unknown_suggestsCandidates() {
        CommandParser.ParseResult result = parser.parse("수학이 너무 어려워요");

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    @DisplayName("빈 명령: 실패")
    void parse_empty_fails() {
        CommandParser.ParseResult result = parser.parse("  ");

        assertThat(result.ok()).isFalse();
    }

    @Test
    @DisplayName("공지 작성: 따옴표 제목 추출")
    void parse_notice_extractsTitle() {
        CommandParser.ParseResult result = parser.parse("공지 작성 \"방학 안내\" 내용: 여름방학 안내입니다.");

        assertThat(result.ok()).isTrue();
        assertThat(result.params()).containsEntry("title", "방학 안내");
        assertThat(result.params()).containsEntry("content", "여름방학 안내입니다.");
    }
}
