package com.rea.academy.service;

import com.rea.academy.domain.member.*;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 회원·학생 관리 서비스.
 * 비투의 인증키 방식을 구현:
 * - 원장/강사: 직접 인증키 발급
 * - 학생: 등록 시 인증키 자동 발급 → 학부모가 그 키로 로그인
 *
 * 화면-기능 분리: 순수 업무 로직. UI 프레임워크 의존 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final ReaMemberRepository memberRepository;
    private final ReaStudentRepository studentRepository;

    private static final String KEY_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int KEY_LENGTH = 10;
    private final SecureRandom random = new SecureRandom();

    /** 인증키 생성 (영문 소문자 + 숫자 10자리) */
    private String generateAuthKey() {
        StringBuilder sb = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            sb.append(KEY_CHARS.charAt(random.nextInt(KEY_CHARS.length())));
        }
        return sb.toString();
    }

    /** 중복되지 않는 인증키 생성 */
    private String generateUniqueMemberKey() {
        String key;
        do {
            key = generateAuthKey();
        } while (memberRepository.existsByAuthKey(key));
        return key;
    }

    private String generateUniqueStudentKey() {
        String key;
        do {
            key = generateAuthKey();
        } while (studentRepository.existsByAuthKey(key));
        return key;
    }

    // ===== 원장·강사 관리 =====

    @Transactional
    public MemberDto.Response createMember(MemberDto.CreateRequest req) {
        // 원장은 학원당 1명만 허용 (2명째 등록 시 예외)
        if (req.getRole() == ReaRole.ACADEMY_OWNER) {
            List<ReaMember> owners = memberRepository
                    .findByAcademyIdAndRoleAndActiveTrue(req.getAcademyId(), ReaRole.ACADEMY_OWNER);
            if (!owners.isEmpty()) {
                throw new IllegalStateException("이미 원장이 등록되어 있습니다.");
            }
        }

        ReaMember member = ReaMember.builder()
                .academyId(req.getAcademyId())
                .role(req.getRole())
                .name(req.getName())
                .phone(req.getPhone())
                .authKey(generateUniqueMemberKey())
                .build();

        ReaMember saved = memberRepository.save(member);
        return toMemberResponse(saved, false);
    }

    public List<MemberDto.Response> getMembers(Long academyId, ReaRole role) {
        List<ReaMember> members = (role == null)
                ? memberRepository.findByAcademyIdAndActiveTrue(academyId)
                : memberRepository.findByAcademyIdAndRoleAndActiveTrue(academyId, role);
        return members.stream()
                .map(m -> toMemberResponse(m, false))
                .collect(Collectors.toList());
    }

    public MemberDto.Response getMemberDetail(Long academyId, Long memberId) {
        ReaMember member = memberRepository.findById(memberId)
                .filter(m -> m.getActive() && m.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        return toMemberResponse(member, true);
    }

    @Transactional
    public void deactivateMember(Long academyId, Long memberId) {
        ReaMember member = memberRepository.findById(memberId)
                .filter(m -> m.getActive() && m.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        member.deactivate();
    }

    @Transactional
    public MemberDto.Response renewMemberKey(Long academyId, Long memberId) {
        ReaMember member = memberRepository.findById(memberId)
                .filter(m -> m.getActive() && m.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        member.renewAuthKey(generateUniqueMemberKey());
        return toMemberResponse(member, true);
    }

    // ===== 학생 관리 =====

    @Transactional
    public StudentDto.DetailResponse createStudent(StudentDto.CreateRequest req) {
        ReaStudent student = ReaStudent.builder()
                .academyId(req.getAcademyId())
                .name(req.getName())
                .grade(req.getGrade())
                .schoolName(req.getSchoolName())
                .parentName(req.getParentName())
                .parentPhone(req.getParentPhone())
                .authKey(generateUniqueStudentKey())
                .build();

        ReaStudent saved = studentRepository.save(student);
        return toStudentDetail(saved);
    }

    public List<StudentDto.Response> getStudents(Long academyId, String nameKeyword) {
        List<ReaStudent> students = (nameKeyword == null || nameKeyword.isBlank())
                ? studentRepository.findByAcademyIdAndActiveTrue(academyId)
                : studentRepository.findByAcademyIdAndNameContainingAndActiveTrue(academyId, nameKeyword);
        return students.stream()
                .map(this::toStudentResponse)
                .collect(Collectors.toList());
    }

    /**
     * 비활성 학생 목록 조회 (원장 전용).
     * 정책: 비활성화된 학생의 데이터는 삭제되지 않고 보존된다.
     * 일반 목록에서는 제외되며, 원장이 명시적으로 조회할 수 있다.
     * 출결·성적·숙제 이력은 비활성화 후에도 보존되어 조회 가능하다.
     */
    public List<StudentDto.Response> getInactiveStudents(Long academyId) {
        return studentRepository.findByAcademyIdAndActiveFalse(academyId).stream()
                .map(this::toStudentResponse)
                .collect(Collectors.toList());
    }

    public StudentDto.DetailResponse getStudentDetail(Long academyId, Long studentId) {
        ReaStudent student = studentRepository.findById(studentId)
                .filter(s -> s.getActive() && s.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("학생을 찾을 수 없습니다."));
        return toStudentDetail(student);
    }

    @Transactional
    public StudentDto.DetailResponse updateStudent(Long academyId, Long studentId, StudentDto.UpdateRequest req) {
        ReaStudent student = studentRepository.findById(studentId)
                .filter(s -> s.getActive() && s.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("학생을 찾을 수 없습니다."));

        // 이름·학년·학교·학부모 정보 수정 (인증키는 별도 재발급)
        student.updateInfo(req.getName(), req.getGrade(), req.getSchoolName());
        if (req.getParentName() != null || req.getParentPhone() != null) {
            student.updateParentInfo(
                req.getParentName() != null ? req.getParentName() : student.getParentName(),
                req.getParentPhone() != null ? req.getParentPhone() : student.getParentPhone());
        }
        return toStudentDetail(student);
    }

    @Transactional
    public void deactivateStudent(Long academyId, Long studentId) {
        ReaStudent student = studentRepository.findById(studentId)
                .filter(s -> s.getActive() && s.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("학생을 찾을 수 없습니다."));
        // 비투 정책: 삭제 시 모든 데이터 삭제되며 복구 불가 — 안내 후 비활성화
        student.deactivate();
    }

    @Transactional
    public StudentDto.DetailResponse renewStudentKey(Long academyId, Long studentId) {
        ReaStudent student = studentRepository.findById(studentId)
                .filter(s -> s.getActive() && s.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("학생을 찾을 수 없습니다."));
        student.renewAuthKey(generateUniqueStudentKey());
        return toStudentDetail(student);
    }

    // ===== 인증키 로그인 =====

    /**
     * 인증키 로그인.
     * - 원장/강사 인증키 → 해당 역할로 로그인
     * - 학생 인증키 → 학부모(PARENT) 역할로 로그인
     */
    public MemberDto.KeyLoginResponse loginByKey(String authKey) {
        // 1. 원장·강사 인증키 확인
        var memberOpt = memberRepository.findByAuthKeyAndActiveTrue(authKey);
        if (memberOpt.isPresent()) {
            ReaMember member = memberOpt.get();
            return MemberDto.KeyLoginResponse.builder()
                    .memberId(member.getId())
                    .academyId(member.getAcademyId())
                    .role(member.getRole())
                    .name(member.getName())
                    .build();
        }

        // 2. 학생 인증키 확인 → 학부모 로그인
        var studentOpt = studentRepository.findByAuthKeyAndActiveTrue(authKey);
        if (studentOpt.isPresent()) {
            ReaStudent student = studentOpt.get();
            return MemberDto.KeyLoginResponse.builder()
                    .academyId(student.getAcademyId())
                    .role(ReaRole.PARENT)
                    .name(student.getParentName() != null ? student.getParentName()
                            : student.getName() + " 학부모")
                    .studentId(student.getId())
                    .build();
        }

        throw new IllegalArgumentException("유효하지 않은 인증키입니다.");
    }

    // ===== DTO 변환 =====

    private MemberDto.Response toMemberResponse(ReaMember m, boolean includeKey) {
        return MemberDto.Response.builder()
                .id(m.getId())
                .academyId(m.getAcademyId())
                .role(m.getRole())
                .name(m.getName())
                .phone(m.getPhone())
                .authKey(includeKey ? m.getAuthKey() : null)
                .active(m.getActive())
                .build();
    }

    private StudentDto.Response toStudentResponse(ReaStudent s) {
        return StudentDto.Response.builder()
                .id(s.getId())
                .academyId(s.getAcademyId())
                .name(s.getName())
                .grade(s.getGrade())
                .schoolName(s.getSchoolName())
                .parentName(s.getParentName())
                .parentPhone(s.getParentPhone())
                .active(s.getActive())
                .build();
    }

    private StudentDto.DetailResponse toStudentDetail(ReaStudent s) {
        return StudentDto.DetailResponse.builder()
                .id(s.getId())
                .academyId(s.getAcademyId())
                .name(s.getName())
                .grade(s.getGrade())
                .schoolName(s.getSchoolName())
                .parentName(s.getParentName())
                .parentPhone(s.getParentPhone())
                .authKey(s.getAuthKey())
                .active(s.getActive())
                .build();
    }
}
