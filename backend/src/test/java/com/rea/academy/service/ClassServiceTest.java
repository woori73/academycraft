package com.rea.academy.service;

import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 반 관리 서비스 테스트 (H2 인메모리).
 * 회귀 확인: MemberService 기존 기능이 깨지지 않았는지도 함께 검증.
 */
@SpringBootTest
@Transactional
class ClassServiceTest {

    @Autowired
    private ClassService classService;

    @Autowired
    private MemberService memberService;

    private Long teacherId;
    private Long studentId1;
    private Long studentId2;

    private static final Long ACADEMY = 1L;
    private static final Long OTHER_ACADEMY = 2L;

    @BeforeEach
    void setUp() {
        MemberDto.Response teacher = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY)
                        .role(ReaRole.TEACHER)
                        .name("김강사")
                        .phone("010-1234-5678")
                        .build());
        teacherId = teacher.getId();

        studentId1 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("홍학생").grade("고2")
                        .build()).getId();
        studentId2 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("박학생").grade("고1")
                        .build()).getId();
    }

    private ClassDto.CreateRequest baseClassRequest() {
        return ClassDto.CreateRequest.builder()
                .name("수학 A반")
                .description("고등 수학")
                .teacherId(teacherId)
                .weekdays(List.of("MON", "WED", "FRI"))
                .startTime("14:00")
                .endTime("16:00")
                .room("201호")
                .capacity(10)
                .build();
    }

    @Test
    @DisplayName("반 생성 성공")
    void createClass() {
        ClassDto.DetailResponse res = classService.createClass(ACADEMY, baseClassRequest());

        assertThat(res.getId()).isNotNull();
        assertThat(res.getName()).isEqualTo("수학 A반");
        assertThat(res.getTeacherName()).isEqualTo("김강사");
        assertThat(res.getWeekdays()).containsExactly("MON", "WED", "FRI");
        assertThat(res.getStartTime()).isEqualTo("14:00");
        assertThat(res.getEndTime()).isEqualTo("16:00");
        assertThat(res.getActive()).isTrue();
        assertThat(res.getStudentCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("반 생성 검증: 이름 필수, 담당강사 필수")
    void createClass_validation() {
        ClassDto.CreateRequest noName = baseClassRequest();
        noName.setName(" ");
        assertThatThrownBy(() -> classService.createClass(ACADEMY, noName))
                .isInstanceOf(IllegalArgumentException.class);

        ClassDto.CreateRequest noTeacher = baseClassRequest();
        noTeacher.setTeacherId(null);
        assertThatThrownBy(() -> classService.createClass(ACADEMY, noTeacher))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("반 생성 검증: 타 학원 강사·학생 회원은 담당강사 불가")
    void createClass_otherAcademyTeacher_rejected() {
        MemberDto.Response otherTeacher = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY)
                        .role(ReaRole.TEACHER)
                        .name("타학원강사")
                        .build());

        ClassDto.CreateRequest req = baseClassRequest();
        req.setTeacherId(otherTeacher.getId());
        assertThatThrownBy(() -> classService.createClass(ACADEMY, req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("반 생성 검증: 요일·시간·정원")
    void createClass_invalidSchedule_rejected() {
        ClassDto.CreateRequest badDay = baseClassRequest();
        badDay.setWeekdays(List.of("FUNDAY"));
        assertThatThrownBy(() -> classService.createClass(ACADEMY, badDay))
                .isInstanceOf(IllegalArgumentException.class);

        ClassDto.CreateRequest badTime = baseClassRequest();
        badTime.setStartTime("18:00");
        badTime.setEndTime("16:00");
        assertThatThrownBy(() -> classService.createClass(ACADEMY, badTime))
                .isInstanceOf(IllegalArgumentException.class);

        ClassDto.CreateRequest badCapacity = baseClassRequest();
        badCapacity.setCapacity(0);
        assertThatThrownBy(() -> classService.createClass(ACADEMY, badCapacity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("반 수정")
    void updateClass() {
        Long id = classService.createClass(ACADEMY, baseClassRequest()).getId();

        ClassDto.UpdateRequest req = ClassDto.UpdateRequest.builder()
                .name("수학 B반")
                .room("202호")
                .build();
        ClassDto.DetailResponse updated = classService.updateClass(ACADEMY, id, req);

        assertThat(updated.getName()).isEqualTo("수학 B반");
        assertThat(updated.getRoom()).isEqualTo("202호");
        // 수정하지 않은 필드는 유지
        assertThat(updated.getStartTime()).isEqualTo("14:00");
    }

    @Test
    @DisplayName("반 목록: 학생 수·담당강사 포함, 비활성 제외")
    void getClasses() {
        Long id1 = classService.createClass(ACADEMY, baseClassRequest()).getId();
        ClassDto.CreateRequest req2 = baseClassRequest();
        req2.setName("영어 A반");
        Long id2 = classService.createClass(ACADEMY, req2).getId();

        classService.assignStudents(ACADEMY, id1, List.of(studentId1, studentId2));
        classService.closeClass(ACADEMY, id2);

        List<ClassDto.Response> active = classService.getClasses(ACADEMY, false);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStudentCount()).isEqualTo(2);
        assertThat(active.get(0).getTeacherName()).isEqualTo("김강사");

        List<ClassDto.Response> all = classService.getClasses(ACADEMY, true);
        assertThat(all).hasSize(2);
    }

    @Test
    @DisplayName("학생 배정: 중복 배정은 무시, 정원은 초과 불가")
    void assignStudents() {
        Long classId = classService.createClass(ACADEMY, baseClassRequest()).getId();

        ClassDto.DetailResponse res = classService.assignStudents(
                ACADEMY, classId, List.of(studentId1, studentId2, studentId1));
        assertThat(res.getStudentCount()).isEqualTo(2);
        assertThat(res.getStudents()).extracting(StudentDto.Response::getName)
                .containsExactlyInAnyOrder("홍학생", "박학생");

        // 정원 초과
        ClassDto.UpdateRequest shrink = ClassDto.UpdateRequest.builder().capacity(2).build();
        classService.updateClass(ACADEMY, classId, shrink);

        Long studentId3 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("최학생")
                        .build()).getId();
        assertThatThrownBy(() -> classService.assignStudents(ACADEMY, classId, List.of(studentId3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("학생 배정: 타 학원 학생 배정 불가")
    void assignStudents_otherAcademyStudent_rejected() {
        Long classId = classService.createClass(ACADEMY, baseClassRequest()).getId();
        Long otherStudent = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).name("타학원생")
                        .build()).getId();

        assertThatThrownBy(() -> classService.assignStudents(ACADEMY, classId, List.of(otherStudent)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("학생 제외")
    void removeStudent() {
        Long classId = classService.createClass(ACADEMY, baseClassRequest()).getId();
        classService.assignStudents(ACADEMY, classId, List.of(studentId1, studentId2));

        classService.removeStudent(ACADEMY, classId, studentId1);

        assertThat(classService.getClassDetail(ACADEMY, classId).getStudentCount()).isEqualTo(1);
        assertThatThrownBy(() -> classService.removeStudent(ACADEMY, classId, studentId1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("반 이동: 기존 반에서 제외되고 대상 반에 배정")
    void moveStudent() {
        Long fromId = classService.createClass(ACADEMY, baseClassRequest()).getId();
        ClassDto.CreateRequest req2 = baseClassRequest();
        req2.setName("영어 A반");
        Long toId = classService.createClass(ACADEMY, req2).getId();

        classService.assignStudents(ACADEMY, fromId, List.of(studentId1));

        ClassDto.DetailResponse moved = classService.moveStudent(ACADEMY, fromId, studentId1, toId);

        assertThat(moved.getId()).isEqualTo(toId);
        assertThat(moved.getStudentCount()).isEqualTo(1);
        assertThat(classService.getClassDetail(ACADEMY, fromId).getStudentCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("반 종료: 비활성화 + 소속 학생 배정 해제, 배정 불가")
    void closeClass() {
        Long classId = classService.createClass(ACADEMY, baseClassRequest()).getId();
        classService.assignStudents(ACADEMY, classId, List.of(studentId1));

        classService.closeClass(ACADEMY, classId);

        ClassDto.DetailResponse detail = classService.getClassDetail(ACADEMY, classId);
        assertThat(detail.getActive()).isFalse();
        assertThat(detail.getStudentCount()).isEqualTo(0);

        assertThatThrownBy(() -> classService.assignStudents(ACADEMY, classId, List.of(studentId1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> classService.closeClass(ACADEMY, classId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("주간 시간표: 요일별 펼침, 요일·시각 정렬")
    void weeklyTimetable() {
        ClassDto.CreateRequest req1 = baseClassRequest(); // 월수금 14:00
        classService.createClass(ACADEMY, req1);

        ClassDto.CreateRequest req2 = baseClassRequest();
        req2.setName("영어 A반");
        req2.setWeekdays(List.of("TUE", "THU"));
        req2.setStartTime("10:00");
        req2.setEndTime("12:00");
        req2.setRoom("301호");
        classService.createClass(ACADEMY, req2);

        List<ClassDto.TimetableEntry> entries = classService.getWeeklyTimetable(ACADEMY);

        // 월수금(3) + 화목(2) = 5개 항목
        assertThat(entries).hasSize(5);
        assertThat(entries).extracting(ClassDto.TimetableEntry::getDayOfWeek)
                .containsExactly("MON", "TUE", "WED", "THU", "FRI");
        assertThat(entries.get(0).getClassName()).isEqualTo("수학 A반");
        assertThat(entries.get(1).getStartTime()).isEqualTo("10:00");
        assertThat(entries.get(1).getRoom()).isEqualTo("301호");
    }

    @Test
    @DisplayName("주간 시간표: 종료된 반·시간표 미지정 반 제외")
    void weeklyTimetable_excludesClosedAndUnscheduled() {
        ClassDto.CreateRequest closed = baseClassRequest();
        closed.setName("종료반");
        Long closedId = classService.createClass(ACADEMY, closed).getId();
        classService.closeClass(ACADEMY, closedId);

        ClassDto.CreateRequest noSchedule = baseClassRequest();
        noSchedule.setName("미편성반");
        noSchedule.setWeekdays(null);
        noSchedule.setStartTime(null);
        noSchedule.setEndTime(null);
        classService.createClass(ACADEMY, noSchedule);

        List<ClassDto.TimetableEntry> entries = classService.getWeeklyTimetable(ACADEMY);
        assertThat(entries).extracting(ClassDto.TimetableEntry::getClassName)
                .doesNotContain("종료반", "미편성반");
    }

    @Test
    @DisplayName("학원 격리: 타 학원은 반 조회·수정 불가")
    void academyIsolation() {
        Long classId = classService.createClass(ACADEMY, baseClassRequest()).getId();

        assertThat(classService.getClasses(OTHER_ACADEMY, false)).isEmpty();
        assertThatThrownBy(() -> classService.getClassDetail(OTHER_ACADEMY, classId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> classService.updateClass(OTHER_ACADEMY, classId,
                ClassDto.UpdateRequest.builder().name("해킹").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(classService.getWeeklyTimetable(OTHER_ACADEMY)).isEmpty();
    }

    // ===== 회귀: 기존 Member 기능 =====

    @Test
    @DisplayName("회귀: 원장·강사·학생 등록과 인증키 로그인 정상 동작")
    void memberRegression() {
        MemberDto.Response owner = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY).role(ReaRole.ACADEMY_OWNER).name("박원장")
                        .build());
        // 생성 응답에는 인증키 미포함 (기존 동작: 상세 조회에서만 반환)
        assertThat(owner.getAuthKey()).isNull();
        String ownerKey = memberService.getMemberDetail(ACADEMY, owner.getId()).getAuthKey();
        assertThat(ownerKey).isNotBlank();

        StudentDto.DetailResponse student = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("회귀학생").parentName("회귀학부모")
                        .build());
        assertThat(student.getAuthKey()).isNotBlank();

        MemberDto.KeyLoginResponse ownerLogin = memberService.loginByKey(ownerKey);
        assertThat(ownerLogin.getRole()).isEqualTo(ReaRole.ACADEMY_OWNER);

        MemberDto.KeyLoginResponse parentLogin = memberService.loginByKey(student.getAuthKey());
        assertThat(parentLogin.getRole()).isEqualTo(ReaRole.PARENT);
        assertThat(parentLogin.getStudentId()).isEqualTo(student.getId());
    }
}
