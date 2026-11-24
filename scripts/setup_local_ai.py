"""Prepare an optional local Ollama model; no cloud AI key or API needed."""
import json,os,pathlib,shutil,subprocess,time
from urllib.request import Request,urlopen

MODEL=os.environ.get('CAR_PAIRING_VOICE_MODEL','qwen3:4b')
def ready():
 try:
  with urlopen('http://127.0.0.1:11434/api/tags',timeout=2) as r:return json.load(r)
 except (OSError,ValueError):return None

def main():
 executable=shutil.which('ollama')
 if not executable and os.name=='nt':
  candidate=pathlib.Path(os.environ.get('LOCALAPPDATA',''))/'Programs/Ollama/ollama.exe'
  if candidate.is_file():executable=str(candidate)
 if not executable:raise SystemExit('Ollama를 https://ollama.com/download 에서 설치한 뒤 다시 실행해 주세요.')
 if ready() is None:
  log=open(pathlib.Path(__file__).resolve().parents[1] / 'local-ai.log','a',encoding='utf-8')
  subprocess.Popen([executable,'serve'],stdout=log,stderr=log)
  for _ in range(30):
   if ready() is not None:break
   time.sleep(1)
  else:raise SystemExit('Ollama 실행에 실패했습니다. local-ai.log를 확인해 주세요.')
 print('로컬 AI 모델 준비:',MODEL,flush=True)
 models=ready() or {}
 if MODEL not in [m.get('name') for m in models.get('models',[])]:subprocess.run([executable,'pull',MODEL],check=True)
 # Warm the model before a driver makes a request.
 body={'model':MODEL,'stream':False,'keep_alive':'30m'}
 with urlopen(Request('http://127.0.0.1:11434/api/generate',data=json.dumps(body).encode(),headers={'Content-Type':'application/json'}),timeout=180) as r:json.load(r)
 print('준비 완료. 로컬 AI가 운전자의 자연스러운 요청을 해석합니다.')
if __name__=='__main__':main()
