"""DEV HELPER: drive the phone UI over adb (uiautomator dump + input tap/text). Not part of the app.
  python3 adb_ui.py dump              -> list visible texts
  python3 adb_ui.py tap "Set up everything"   -> tap the element whose text/content-desc contains the label
  python3 adb_ui.py text "hello world"        -> type text (spaces handled)
Needs: adb authorized to the phone. Screenshots: adb exec-out screencap -p > shot.png (Claude can Read the PNG)."""
import subprocess,re,sys
def sh(c): return subprocess.run(["adb","shell",c],capture_output=True,text=True).stdout
def dump():
    sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1"); return sh("cat /sdcard/ui.xml")
def find(label, x=None):
    x=x or dump()
    for m in re.finditer(r'<node[^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
        if label.lower() in (m[1]+" "+m[2]).lower():
            return ((int(m[3])+int(m[5]))//2,(int(m[4])+int(m[6]))//2)
def tap(label):
    p=find(label)
    if not p: print("NOT FOUND",label); return False
    sh(f"input tap {p[0]} {p[1]}"); return True
def text(s): sh("input text '%s'"%s.replace(" ","%s"))
if __name__=="__main__":
    a=sys.argv[1]
    if a=="tap": print(tap(sys.argv[2]))
    elif a=="text": text(sys.argv[2])
    elif a=="dump": print(re.findall(r'text="([^"]+)"',dump()))
