package com.rea.academy.service;

import com.rea.academy.domain.admin.AuditActionType;
import com.rea.academy.domain.admin.ReaAcademy;
import com.rea.academy.domain.admin.ReaAcademyRepository;
import com.rea.academy.domain.admin.ReaAuditLog;
import com.rea.academy.domain.admin.ReaAuditLogRepository;
import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.member.ReaStudent;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.dto.AdminDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 학원 관리·관리자 기능 서비스 (8단계).
 * - 학원 기본정보 조회·수정 (이름·전화·주소·로고 키)
 * - 지점 관리: 본원·지점 관계 (parentAcademyId)
 * - 사용자·권한 관리: 역할별 사용자 목록, 강사 권한 변경(원장만), 학부모 연결 확인
 * - 변경 이력(감사 로그) 조회
 *
 * 격리 규칙: 모든 조회·수정은 academyId로 스코핑. 타 학원 데이터 접근 불가.
 * 기존 코드 수정 없음: 권한 변경은 ReaMember 복사+저장(merge)으로 수행하고,
 * 감사 로그는 AuditRecorder/AuditLogAspect로 기록한다.
 * 실제 외부 메시지 발송 없음. 수납·결제 기능 없음.
 * 화면-기능 분리: 순수 업무 로직. UI 프레임워크 의존 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    private final ReaAcademyRepository academyRepository;
    private final ReaAuditLogRepository auditLogRepository;
    private final ReaMemberRepository memberRepository;
    private final ReaStudentRepository studentRepository;
    private final AuditRecorder auditRecorder;

    // ================= 학원 기본정보 =================

    /**
     * 학원 정보 조회.
     * 미등록 학원이면 400 (PUT으로 먼저 등록).
     */
    public AdminDto.AcademyInfoResponse getAcademyInfo(Long academyId) {
        return toAcademyInfoResponse(requireAcademy(academyId));
    }

    /**
     * 학원 정보 등록·수정 (upsert).
     * - 미등록 학원이면 신규 등록 ("학원 등록", name 필수)
     * - 등록된 학원이면 제공된 항목만 수정 (null은 변경 없음)
     * - 변경 시 감사 로그(ACADEMY_INFO_UPDATED) 기록
     */
    @Transactional
    public AdminDto.AcademyInfoResponse saveAcademyInfo(
            Long academyId, AdminDto.AcademyInfoUpdateRequest req) {
        ReaAcademy academy = academyRepository.findById(academyId).orElse(null);
        boolean created = (academy == null);

        if (created) {
            if (req.getName() == null || req.getName().isBlank()) {
                throw new IllegalArgumentException("학원 이름은 필수입니다.");
            }
            academy = academyRepository.save(ReaAcademy.builder()
                    .id(academyId)
                    .name(req.getName().trim())
                    .phone(blankToNull(req.getPhone()))
                    .address(blankToNull(req.getAddress()))
                    .logoKey(blankToNull(req.getLogoKey()))
                    .memo(blankToNull(req.getMemo()))
                    .build());
        } else {
            String name = null;
            if (req.getName() != null) {
                if (req.getName().isBlank()) {
                    throw new IllegalArgumentException("학원 이름은 비워 둘 수 없습니다.");
                }
                name = req.getName().trim();
            }
            academy.update(name, blankToNull(req.getPhone()), blankToNull(req.getAddress()),
                    blankToNull(req.getLogoKey()), blankToNull(req.getMemo()));
        }

        Actor actor = resolveActor(academyId, req.getActorMemberId());
        auditRecorder.record(academyId, AuditActionType.ACADEMY_INFO_UPDATED, "학원",
                academy.getId(), academy.getName(),
                actor.id(), actor.name(),
                created ? "학원 등록: " + academy.getName()
                        : "학원 정보 수정: " + academy.getName());
        return toAcademyInfoResponse(academy);
    }

    // ================= 지점 관리 =================

    /**
     * 지점 목록 조회 (본원 academyId 기준).
     */
    public List<AdminDto.AcademyInfoResponse> listBranches(Long academyId) {
        return academyRepository.findByParentAcademyIdAndActiveTrueOrderByIdAsc(academyId)
                .stream()
                .map(this::toAcademyInfoResponse)
                .collect(Collectors.toList());
    }

    /**
     * 지점 등록.
     * - 본원(상위 학원)은 먼저 PUT /info로 등록되어 있어야 함 (400)
     * - 새 academyId는 서버가 자동 발급 (등록된 학원 중 최대값 + 1)
     * - 변경 시 감사 로그(BRANCH_CREATED)를 본원·지점 양쪽에 기록
     */
    @Transactional
    public AdminDto.AcademyInfoResponse createBranch(
            Long academyId, AdminDto.BranchCreateRequest req) {
        ReaAcademy parent = academyRepository.findById(academyId).orElse(null);
        if (parent == null) {
            throw new IllegalArgumentException(
                    "본원 학원 정보가 먼저 등록되어야 합니다. PUT /info로 등록하세요.");
        }
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("지점 이름은 필수입니다.");
        }

        Long newAcademyId = academyRepository.findMaxId() + 1;
        ReaAcademy branch = academyRepository.save(ReaAcademy.builder()
                .id(newAcademyId)
                .name(req.getName().trim())
                .phone(blankToNull(req.getPhone()))
                .address(blankToNull(req.getAddress()))
                .logoKey(blankToNull(req.getLogoKey()))
                .memo(blankToNull(req.getMemo()))
                .parentAcademyId(academyId)
                .build());

        Actor actor = resolveActor(academyId, req.getActorMemberId());
        String detail = "지점 등록: " + branch.getName()
                + " (본원: " + parent.getName() + ", 새 학원 ID: " + newAcademyId + ")";
        auditRecorder.record(academyId, AuditActionType.BRANCH_CREATED, "학원",
                parent.getId(), parent.getName(), actor.id(), actor.name(), detail);
        auditRecorder.record(newAcademyId, AuditActionType.BRANCH_CREATED, "학원",
                branch.getId(), branch.getName(), actor.id(), actor.name(), detail);
        return toAcademyInfoResponse(branch);
    }

    // ================= 사용자·권한 관리 =================

    /**
     * 역할별 사용자 목록.
     * - role 미지정: 원장·강사 전체
     * - role=OWNER: 원장만 / role=TEACHER: 강사만
     * - role=PARENT: 학부모 연결 목록 (학생별 학부모 성명·연락처·연결 여부)
     */
    public AdminDto.UserListResponse listUsers(Long academyId, String roleParam) {
        if (roleParam != null && parseRoleParam(roleParam) == ReaRole.PARENT) {
            return AdminDto.UserListResponse.builder()
                    .staff(List.of())
                    .parents(listParentLinks(academyId))
                    .build();
        }
        ReaRole role = roleParam == null ? null : parseRoleParam(roleParam);
        List<ReaMember> members = (role == null)
                ? memberRepository.findByAcademyIdAndActiveTrue(academyId)
                : memberRepository.findByAcademyIdAndRoleAndActiveTrue(academyId, role);
        List<AdminDto.StaffResponse> staff = members.stream()
                .map(this::toStaffResponse)
                .collect(Collectors.toList());
        return AdminDto.UserListResponse.builder()
                .staff(staff)
                .parents(List.of())
                .build();
    }

    /**
     * 권한 변경 (원장만 수행 가능).
     * - actorMemberId: 요청자. 같은 학원의 활성 ACADEMY_OWNER여야 함 (아니면 400)
     * - TEACHER → ACADEMY_OWNER: 승격 허용. 원장 교체(인수인계)는
     *   "후임 승격 → 전임 강등" 2단계로 수행한다 (각 단계 감사 로그 기록)
     * - ACADEMY_OWNER → TEACHER: 마지막 1명인 원장은 강등 불가 (409, 원장 부재 방지)
     * - 변경 시 감사 로그(MEMBER_ROLE_CHANGED) 기록
     *
     * 기존 ReaMember 코드를 수정하지 않기 위해 역할이 바뀐 복사본을 저장(merge)한다.
     */
    @Transactional
    public AdminDto.StaffResponse changeMemberRole(
            Long academyId, Long memberId, AdminDto.RoleChangeRequest req) {
        if (req.getActorMemberId() == null) {
            throw new IllegalArgumentException("요청자(actorMemberId)는 필수입니다.");
        }
        ReaMember actor = memberRepository.findById(req.getActorMemberId())
                .filter(m -> academyId.equals(m.getAcademyId())
                        && Boolean.TRUE.equals(m.getActive()))
                .orElseThrow(() -> new IllegalArgumentException("요청자를 찾을 수 없습니다."));
        if (actor.getRole() != ReaRole.ACADEMY_OWNER) {
            throw new IllegalArgumentException("권한 변경은 원장만 수행할 수 있습니다.");
        }

        ReaMember target = memberRepository.findById(memberId)
                .filter(m -> academyId.equals(m.getAcademyId())
                        && Boolean.TRUE.equals(m.getActive()))
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        ReaRole newRole = parseStaffRole(req.getRole());
        if (target.getRole() == newRole) {
            throw new IllegalArgumentException("이미 " + roleLabel(newRole) + " 역할입니다.");
        }

        List<ReaMember> owners = memberRepository
                .findByAcademyIdAndRoleAndActiveTrue(academyId, ReaRole.ACADEMY_OWNER);
        if (target.getRole() == ReaRole.ACADEMY_OWNER && owners.size() <= 1) {
            throw new IllegalStateException("마지막 원장의 권한을 변경할 수 없습니다.");
        }

        ReaRole before = target.getRole();
        ReaMember updated = memberRepository.save(copyWithRole(target, newRole));

        auditRecorder.record(academyId, AuditActionType.MEMBER_ROLE_CHANGED, "회원",
                updated.getId(), updated.getName(),
                actor.getId(), actor.getName(),
                "권한 변경: " + updated.getName()
                        + " (" + roleLabel(before) + " → " + roleLabel(newRole) + ")");
        return toStaffResponse(updated);
    }

    // ================= 변경 이력 조회 =================

    /**
     * 변경 이력 조회 (관리자용, 최신순).
     * actionType 파라미터(선택, 대소문자 무시)로 종류별 필터 가능.
     */
    public AdminDto.AuditLogListResponse listAuditLogs(Long academyId, String actionTypeParam) {
        List<ReaAuditLog> logs;
        if (actionTypeParam == null || actionTypeParam.isBlank()) {
            logs = auditLogRepository.findByAcademyIdOrderByIdDesc(academyId);
        } else {
            AuditActionType actionType = AuditActionType.parse(actionTypeParam);
            logs = auditLogRepository.findByAcademyIdAndActionTypeOrderByIdDesc(
                    academyId, actionType);
        }
        List<AdminDto.AuditLogResponse> responses = logs.stream()
                .map(this::toAuditLogResponse)
                .collect(Collectors.toList());
        return AdminDto.AuditLogListResponse.builder()
                .logs(responses)
                .totalCount(responses.size())
                .build();
    }

    // ================= 내부 유틸 =================

    private ReaAcademy requireAcademy(Long academyId) {
        return academyRepository.findById(academyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "학원 정보가 등록되지 않았습니다. PUT /info로 먼저 등록하세요: " + academyId));
    }

    /**
     * 감사 로그 행위자 해석. actorMemberId가 없거나 유효하지 않으면 시스템(null)으로 기록.
     * (학원 정보 등록·지점 등록은 원장이 아닐 수도 있어 관대하게 처리.
     *  단, 권한 변경은 changeMemberRole에서 별도로 엄격 검증한다.)
     */
    private Actor resolveActor(Long academyId, Long actorMemberId) {
        if (actorMemberId == null) {
            return new Actor(null, null);
        }
        return memberRepository.findById(actorMemberId)
                .filter(m -> academyId.equals(m.getAcademyId())
                        && Boolean.TRUE.equals(m.getActive()))
                .map(m -> new Actor(m.getId(), m.getName()))
                .orElse(new Actor(null, null));
    }

    private record Actor(Long id, String name) {
    }

    private List<AdminDto.ParentLinkResponse> listParentLinks(Long academyId) {
        return studentRepository.findByAcademyIdAndActiveTrue(academyId).stream()
                .map(this::toParentLinkResponse)
                .collect(Collectors.toList());
    }

    /**
     * 기존 ReaMember 수정 없이 역할을 바꾸는 복사본 생성.
     * id가 유지되므로 save()는 merge(UPDATE)로 동작한다.
     */
    private ReaMember copyWithRole(ReaMember source, ReaRole newRole) {
        return ReaMember.builder()
                .id(source.getId())
                .academyId(source.getAcademyId())
                .role(newRole)
                .name(source.getName())
                .authKey(source.getAuthKey())
                .phone(source.getPhone())
                .active(source.getActive())
                .createdAt(source.getCreatedAt())
                .build();
    }

    /**
     * ?role= 파라미터 파싱. OWNER/ACADEMY_OWNER/TEACHER/PARENT (대소문자 무시).
     */
    private ReaRole parseRoleParam(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("role 파라미터가 비어 있습니다.");
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "OWNER", "ACADEMY_OWNER" -> ReaRole.ACADEMY_OWNER;
            case "TEACHER" -> ReaRole.TEACHER;
            case "PARENT" -> ReaRole.PARENT;
            default -> throw new IllegalArgumentException("알 수 없는 역할입니다: " + value);
        };
    }

    /**
     * 권한 변경 대상 역할 파싱. 학부모(P) 역할은 회원 권한 변경 대상이 아니다.
     */
    private ReaRole parseStaffRole(String value) {
        ReaRole role = parseRoleParam(value);
        if (role == ReaRole.PARENT) {
            throw new IllegalArgumentException(
                    "학부모 역할은 학생 인증키로 로그인하므로 권한 변경 대상이 아닙니다.");
        }
        return role;
    }

    private String roleLabel(ReaRole role) {
        return switch (role) {
            case ACADEMY_OWNER -> "원장";
            case TEACHER -> "강사";
            case PARENT -> "학부모";
        };
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private AdminDto.AcademyInfoResponse toAcademyInfoResponse(ReaAcademy a) {
        return AdminDto.AcademyInfoResponse.builder()
                .id(a.getId())
                .name(a.getName())
                .phone(a.getPhone())
                .address(a.getAddress())
                .logoKey(a.getLogoKey())
                .memo(a.getMemo())
                .parentAcademyId(a.getParentAcademyId())
                .branch(a.isBranch())
                .active(a.getActive())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }

    private AdminDto.StaffResponse toStaffResponse(ReaMember m) {
        return AdminDto.StaffResponse.builder()
                .id(m.getId())
                .role(m.getRole())
                .roleLabel(roleLabel(m.getRole()))
                .name(m.getName())
                .phone(m.getPhone())
                .active(m.getActive())
                .createdAt(m.getCreatedAt())
                .build();
    }

    private AdminDto.ParentLinkResponse toParentLinkResponse(ReaStudent s) {
        boolean connected = s.getParentName() != null && !s.getParentName().isBlank();
        return AdminDto.ParentLinkResponse.builder()
                .studentId(s.getId())
                .studentName(s.getName())
                .grade(s.getGrade())
                .parentName(s.getParentName())
                .parentPhone(s.getParentPhone())
                .connected(connected)
                .build();
    }

    private AdminDto.AuditLogResponse toAuditLogResponse(ReaAuditLog l) {
        return AdminDto.AuditLogResponse.builder()
                .id(l.getId())
                .actionType(l.getActionType().name())
                .actionTypeLabel(l.getActionType().getLabel())
                .targetType(l.getTargetType())
                .targetId(l.getTargetId())
                .targetName(l.getTargetName())
                .actorId(l.getActorId())
                .actorName(l.getActorName())
                .detail(l.getDetail())
                .createdAt(l.getCreatedAt())
                .build();
    }
}
