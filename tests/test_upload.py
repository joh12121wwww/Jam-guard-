"""Exercise the Termux upload helper against a disposable local bare remote only."""
from pathlib import Path
import subprocess, tempfile
source=Path(__file__).resolve().parents[1]
def run(*args, **kw):
 return subprocess.run(args,check=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,**kw)
with tempfile.TemporaryDirectory(prefix='jamguard-upload-test-') as tmp:
 root=Path(tmp);remote=root/'remote.git';repo=root/'checkout with spaces'
 run('git','init','--bare','--initial-branch=main',str(remote))
 run('git','clone',str(remote),str(repo))
 run('git','-C',str(repo),'config','user.name','Local Test')
 run('git','-C',str(repo),'config','user.email','local@example.invalid')
 (repo/'app/src/old').mkdir(parents=True);(repo/'app/src/old/obsolete.txt').write_text('old')
 (repo/'keep.txt').write_text('unrelated work')
 run('git','-C',str(repo),'add','.')
 run('git','-C',str(repo),'commit','-m','fixture')
 run('git','-C',str(repo),'push','origin','main')
 run('bash',str(source/'UPLOAD_WITH_TERMUX.sh'),str(repo))
 assert not (repo/'app/src/old').exists()
 assert (repo/'app/src/main/assets/app.js').is_file()
 assert (repo/'.github/workflows/android.yml').is_file()
 assert (repo/'gradle/wrapper/gradle-wrapper.jar').is_file()
 assert (repo/'docs/DEVICE_PROTOCOL.md').is_file()
 assert not (repo/'docs/docs').exists()
 assert (repo/'keep.txt').read_text()=='unrelated work'
 assert not run('git','-C',str(repo),'status','--porcelain').stdout.strip()
 assert 'No changes to upload' in run('bash',str(source/'UPLOAD_WITH_TERMUX.sh'),str(repo)).stdout
 (repo/'keep.txt').write_text('unsaved change')
 result=subprocess.run(['bash',str(source/'UPLOAD_WITH_TERMUX.sh'),str(repo)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 assert result.returncode!=0 and 'uncommitted work' in result.stdout
 assert (repo/'keep.txt').read_text()=='unsaved change'
 print('PASS: Termux upload helper, local push, repeated upload, paths with spaces and dirty-checkout protection')
