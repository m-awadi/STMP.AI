# Regenerates the palette and contrast fixture from the Android source at the baseline SHA. Run from the repo root.
# usage: python3 -I ios/scripts/gen-tokens.py
import re,subprocess,json
A="/Users/mohamed/Developer/STMP.AI-integration"; S="ce38676ecf55b033a050a24b2236ad5633908df6"
def show(p): return subprocess.run(["git","-C",A,"show",f"{S}:{p}"],capture_output=True,text=True,check=True).stdout
pal=show("app/src/main/java/com/mido/phonebridge/ui/tokens/Palette.kt")
roles=re.findall(r'^\s+(\w+) = c\(R\.color\.(stmp_\w+)\)',pal,re.M)
assert len(roles)==44, len(roles)
N=len(roles)
def colors(p): return dict(re.findall(r'<color name="(stmp_\w+)">(#[0-9A-Fa-f]+)</color>',show(p)))
L=colors("app/src/main/res/values/colors.xml"); D=colors("app/src/main/res/values-night/colors.xml")
def argb(h):
    h=h[1:]; return int("FF"+h,16) if len(h)==6 else int(h,16)
out=["import SwiftUI","","// GENERATED from Android `res/values{,-night}/colors.xml` at the baseline SHA (the 44 colour roles of Palette.kt).","// Do not edit by hand. `argb` is 0xAARRGGBB; alpha roles keep their alpha.","","/// One colour role. `argb` is the source of truth; `color` is the sRGB `Color`.","struct ColorToken: Sendable, Hashable {","  nonisolated let argb: UInt32","  nonisolated init(_ argb: UInt32) { self.argb = argb }","  nonisolated var alpha: Double { Double((argb >> 24) & 0xFF) / 255 }","  nonisolated var red: Double { Double((argb >> 16) & 0xFF) / 255 }","  nonisolated var green: Double { Double((argb >> 8) & 0xFF) / 255 }","  nonisolated var blue: Double { Double(argb & 0xFF) / 255 }","  var color: Color { Color(.sRGB, red: red, green: green, blue: blue, opacity: alpha) }","}","","/// The Instrument palette (ui.md 1.2). Light and dark follow the system; there is no in-app switch.","struct Palette: Sendable, Hashable {","  nonisolated let isDark: Bool"]
for n,_ in roles: out.append(f"  nonisolated let {n}: ColorToken")
def lit(d,isdark):
    lines=[f"  nonisolated static let {'dark' if isdark else 'light'} = Palette(","    isDark: %s,"%("true" if isdark else "false")]
    for i,(n,x) in enumerate(roles): lines.append(f"    {n}: ColorToken(0x{argb(d[x]):08X})"+("," if i<N-1 else ""))
    lines.append("  )"); return lines
out+=["","  nonisolated static let roleNames: [String] = ["]+[f'    "{n}",' for n,_ in roles]+["  ]","","  /// Role lookup by name, for the contrast fixture.","  nonisolated func token(_ role: String) -> ColorToken? {","    switch role {"]
out+=[f'    case "{n}": return {n}' for n,_ in roles]+["    default: return nil","    }","  }",""]
out+=lit(L,False)+[""]+lit(D,True)+["}",""]
open("ios/App/Sources/DesignSystem/Tokens/Palette.generated.swift","w").write("\n".join(out))
ct=show("app/src/test/java/com/mido/phonebridge/ContrastTest.kt").split("\n")
def ln(pat):
    for i,l in enumerate(ct):
        if re.search(pat,l): return i+1
    raise SystemExit("no "+pat)
pairs=[]
def add(name,fg,bg,mn,line_pat,dark=False,over=None):
    e={"name":name,"fg":fg,"bg":bg,"min":mn,"darkOnly":dark,"_source":f"android:app/src/test/java/com/mido/phonebridge/ContrastTest.kt:{ln(line_pat)}@{S}","_expect":[f"it.{fg}"]}
    if over: e["bgOver"]=over
    pairs.append(e)
for n in ("body","panel","sunken"):
    for r in ("ink","etched","okText","cautionText","signalText"): add(f"{r} on {n}",r,n,4.5,r'add\(p\("%s on \$n"'%r)
for nm,fg,bg,mn,dk in [("etched on tapePaper","etched","tapePaper",4.5,0),("ink on tapePaper","ink","tapePaper",4.5,0),("onInk on ink","onInk","ink",4.5,0),("onSignal on signal","onSignal","signal",4.5,0),("okText on okSurface","okText","okSurface",4.5,0),("cautionText on cautionSurface","cautionText","cautionSurface",4.5,0),("signalText on signalSurface","signalText","signalSurface",4.5,0),("ink on okSurface","ink","okSurface",4.5,0),("ink on cautionSurface","ink","cautionSurface",4.5,0),("ink on signalSurface","ink","signalSurface",4.5,0),("onInk on okText","onInk","okText",4.5,0),("onInk on cautionText","onInk","cautionText",4.5,0),("onInk on signalText","onInk","signalText",4.5,0),("readoutText on readout","readoutText","readout",4.5,0),("readoutCommand on readout","readoutCommand","readout",4.5,0),("readoutMuted on readout","readoutMuted","readout",4.5,0),("readoutDanger on readout","readoutDanger","readout",4.5,0),("readoutString on readout","readoutString","readout",4.5,0),("signal underline on readout","signal","readout",3.0,0),("rockerOffText on rockerCapOff","rockerOffText","rockerCapOff",4.5,0)]:
    add(nm,fg,bg,mn,r'add\(p\("%s"'%re.escape(nm),bool(dk))
for n in ("body","panel"):
    for r in ("okGlyph","cautionGlyph","signalGlyph","outline"): add(f"{r} on {n}",r,n,3.0,r'add\(p\("%s on \$n"'%r)
for nm,fg,bg in [("LED ring okText on panel","okText","panel"),("LED ring cautionText on panel","cautionText","panel"),("LED ring signalText on panel","signalText","panel"),("LED ring etched on panel","etched","panel"),("focus on body","focus","body")]:
    add(nm,fg,bg,3.0,r'add\(p\("%s"'%re.escape(nm))
add("rockerCapOff on rockerTrack","rockerCapOff","rockerTrack",3.0,r'add\(p\("rockerCapOff on rockerTrack"',True)
add("rockerTrack edge (outline) on panel","outline","panel",3.0,r'rockerTrack edge')
add("verb chip edge (outline) on sunken","outline","sunken",3.0,r'verb chip edge')
add("ink on verb chip sunken fill","ink","sunken",4.5,r'ink on verb chip sunken fill')
for n in ("body","panel"):
    add(f"progress label (ink) on its fill over {n}","ink",n,4.5,r'progress label \(ink\) on its fill',over={"fg":"ink","bg":n,"alpha":0x38})
    add(f"progress label (ink) on its track over {n}","ink",n,4.5,r'progress label \(ink\) on its track')
json.dump({"_source":f"android:app/src/test/java/com/mido/phonebridge/ContrastTest.kt:{ln('private val pairs')}@{S}","_expect":["pairs"],"pairs":pairs},open("ios/contract/tokens/contrast-pairs.json","w"),indent=2); open("ios/contract/tokens/contrast-pairs.json","a").write("\n")
print(len(pairs),"pairs")
