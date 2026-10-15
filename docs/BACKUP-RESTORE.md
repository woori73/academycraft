# AcademyCraft 백업 복원 절차

## 백업 파일

| 백업 | 파일명 | SHA256 | 크기 |
|---|---|---|---|
| 1차 (마무리 작업 전) | `rea-academy-backup-20261016_014040.tar.gz` | `86b62c38412ce44e7ecacf9180cecee23ff3befbdb93d4302fc6ccb46efc7ed4` | 694K |
| 2차 (마무리 작업 후) | `rea-academy-backup-20261016_020333.tar.gz` | `aecfe2f5659d01e63847968251caa28f4bf0cdcb25e41d6b197992675cfc00fd` | 700K |

## 복원 절차

```bash
# 1. 무결성 검증 (필수)
cd ~/workspace/backups
sha256sum rea-academy-backup-20261016_020333.tar.gz
# 위 표의 SHA256과 일치하는지 확인

# 2. 압축 해제
cd ~/workspace
tar -xzf backups/rea-academy-backup-20261016_020333.tar.gz

# 3. 확인
ls ~/workspace/rea-academy/
```

## 주의사항

- 백업 파일은 절대 삭제하거나 덮어쓰지 마세요
- GitHub 업로드 및 외부 공개 금지 (별도 승인 전까지)
- 복원 후 테스트 실행으로 정상 동작 확인 권장
