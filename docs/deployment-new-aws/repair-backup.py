#!/usr/bin/env python3
"""Repair ONLY AcademyCraft cron on deployed EC2; no DB grants or schema changes."""
import os,re,shlex,subprocess,sys,time
from pathlib import Path

def main():
 if os.geteuid()!=0 or not Path('/etc/academycraft/env').is_file():
  raise SystemExit('Run with sudo on the deployed AcademyCraft EC2, not CloudShell.')
 os.umask(0o077)
 path=Path('/etc/cron.daily/academycraft-backup')
 old=path.read_text()
 # Keep the already configured dedicated destination; never accept arbitrary resources.
 match=re.search(r'^S3_BUCKET=(.*)$',old,re.M)
 if not match:raise SystemExit('Missing backup destination; no change.')
 bucket=shlex.split(match[1])[0]
 if bucket not in ('s3://f2r-deploy-425805163960/backups/academycraft/','s3://f2r-deploy-425805163960/backups/academycraft'):
  raise SystemExit('Unexpected backup destination; no change.')
 # Minimal repair: preserve env, bucket, retention; add least-privilege dump option,
 # explicit pipeline error handling even when inherited errexit is disabled.
 pattern=r'(MYSQL_PWD="\$DB_PASSWORD" mysqldump[^\n]*\\\n\s*--single-transaction)([^\n]*\\\n\s*\| gzip > "\$BACKUP_DIR/academycraft-\$DATE.sql.gz")'
 found=re.search(pattern,old)
 if not found and '# academycraft-backup-v2' not in old:raise SystemExit('Unexpected cron format; no change.')
 if '# academycraft-backup-v2' not in old:
  command=found[0].replace('--single-transaction','--single-transaction --no-tablespaces')
  # Unique name protects earlier good backups; partial output removed on failure.
  new=old[:found.start()]+'if ! '+command+'; then\n  rm -f "$BACKUP_DIR/academycraft-$DATE.sql.gz"\n  echo "AcademyCraft dump failed; backup aborted." >&2\n  exit 1\nfi'+old[found.end():]
  new=new.replace('DATE=$(date +%F)','DATE=$(date +%F-%H%M%S)-$$')
  new=new.replace('gzip -t "$BACKUP_DIR/academycraft-$DATE.sql.gz"','gzip -t "$BACKUP_DIR/academycraft-$DATE.sql.gz" || exit 1')
  new=new.replace('set -euo pipefail','set -euo pipefail\n# academycraft-backup-v2')
  previous=path.with_name('academycraft-backup.before-'+str(time.time_ns()))
  # Outside cron.daily to prevent accidental duplicate cron execution.
  previous=Path('/etc/academycraft')/previous.name
  with previous.open('x') as out:out.write(old)
  previous.chmod(0o600)
  temp=path.with_name('.academycraft-backup.new')
  with temp.open('x') as out:out.write(new)
  temp.chmod(0o700)
  if subprocess.run(['bash','-n',str(temp)],capture_output=True).returncode:
   temp.unlink();raise SystemExit('Backup syntax validation failed; original retained.')
  temp.replace(path)
 result=subprocess.run([str(path)],capture_output=True,text=True)
 if result.returncode:raise SystemExit('Backup FAILED; no initialization permitted. Credentials/output suppressed.')
 print('PASS: AcademyCraft backup repaired and uploaded; no PROCESS grant or DB changes.')
if __name__=='__main__':
 try:main()
 except (OSError,ValueError,IndexError):raise SystemExit('Backup repair stopped; inspect prerequisites privately. No credentials printed.')
