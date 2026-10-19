#!/usr/bin/env python3
"""Regenerates test-fixtures/configs (run from the repo root). Deterministic."""
import json, copy, os
import shutil
for d in ('test-fixtures/configs/valid','test-fixtures/configs/invalid'):
    shutil.rmtree(d, ignore_errors=True); os.makedirs(d)
B = json.load(open('schema/examples/minimal.json')); B.pop('$schema')
V='test-fixtures/configs/valid'; I='test-fixtures/configs/invalid'
man=[]
def dump(o): return (json.dumps(o, indent=2, ensure_ascii=False)+"\n").encode()
def add(d, name, data, outcome, layer, ref, note):
    p=f"{d}/{name}"; open(p,'wb').write(data if isinstance(data,bytes) else dump(data))
    man.append(dict(file=p.split('configs/')[1], expect=outcome, layer=layer, ref=ref, note=note))
def m(f):
    c=copy.deepcopy(B); f(c); return c
pg=lambda c: c['pages'][0]['items']
# valid
add(V,'minimal.json',B,'valid','both','§3','copy of schema/examples/minimal.json')
add(V,'bom.json',b'\xef\xbb\xbf'+dump(B),'valid (BOM stripped; next write has no BOM)','parser','§15 OQ-7','')
add(V,'x-keys.json',m(lambda c:(c.__setitem__('x-note','agent notes'),pg(c)[0].__setitem__('x-color',{'a':[1,2]}))),'valid; x- keys round-trip verbatim','both','§15 OQ-4','')
add(V,'no-revision.json',m(lambda c:c.pop('revision')),'valid','both','§15 OQ-20','')
add(V,'same-app-dock-and-page.json',m(lambda c:c['dock'].append({'id':'a-settings2','type':'app','package':'com.android.settings'})),'valid','both','§15 OQ-5','')
add(V,'same-app-twice-on-page.json',m(lambda c:pg(c).append({'id':'a-settings2','type':'app','package':'com.android.settings','x':3,'y':0})),'valid with warning','kotlin','§3, §15 OQ-5','')
add(V,'unknown-package.json',m(lambda c:pg(c).append({'id':'a-ghost','type':'app','package':'com.example.doesnotexist','x':3,'y':0})),'valid; renders greyed, never launched','both','§3, §15 OQ-28','')
add(V,'bidi-label.json',m(lambda c:pg(c)[0].__setitem__('label','\u202eSettings\u200b')),'valid; bidi/zero-width stripped at render, file untouched','both','§15 OQ-11','')
add(V,'emoji-label.json',m(lambda c:pg(c)[0].__setitem__('label','👨‍👩‍👧 Família 日本')),'valid','both','§15 OQ-11','')
add(V,'grid-12x12-max.json',m(lambda c:(c.__setitem__('grid',{'columns':12,'rows':12}),pg(c)[0].update(x=11,y=11))),'valid','both','§15 OQ-2','')
add(V,'work-profile.json',m(lambda c:pg(c).append({'id':'a-work-gmail','type':'app','package':'com.google.android.gm','profile':'work','x':3,'y':0})),'valid; work item honoured only if "Include work apps" on','both','§15 OQ-29','')
add(V,'revision-lower.json',m(lambda c:c.__setitem__('revision',0)),'valid; accepted even if lower than last seen (hash decides)','both','§15 OQ-19','')
add(V,'max-depth-8.json',m(lambda c:c['pages'][0]['items'][2]['items'][0].__setitem__('tags',['t'])),'valid (exactly depth 8)','parser','§15 OQ-9','folder>item>tags is depth 8')
# invalid
add(I,'empty.json',b'','parse error, B1, last-good kept','parser','§4, §15 OQ-14','')
add(I,'truncated.json',dump(B)[:200],'parse error, B1','parser','§4 step 4, §15 OQ-18','simulates torn read')
add(I,'trailing-garbage.json',dump(B)+b'}}','parse error, B1','parser','§15 OQ-6','')
add(I,'comments.json',b'// note\n'+dump(B),'parse error, B1 (no comments)','parser','§15 OQ-6','')
add(I,'json5-trailing-comma.json',b'{"schemaVersion":1,"pages":[{"id":"home","items":[],}],}\n','parse error, B1','parser','§15 OQ-6','')
add(I,'utf16.json',dump(B).decode().encode('utf-16'),'parse error, B1','parser','§15 OQ-7','')
add(I,'invalid-utf8.json',dump(B).replace(b'"Home"',b'"Ho\xffme"'),'parse error, B1','parser','§15 OQ-11','')
add(I,'lone-surrogate.json',dump(B).replace(b'"Home"',b'"Ho\\ud800me"'),'parse error, B1','parser','§15 OQ-11','')
add(I,'nul-in-label.json',dump(B).replace(b'"Home"',b'"Ho\\u0000me"'),'parse error, B1 (escaped NUL)','parser','§17 Q3','')
add(I,'nul-raw.json',dump(B).replace(b'"Home"',b'"Ho\x00me"'),'parse error, B1 (raw NUL byte: an unescaped control character, so a syntax error for any JSON parser)','parser','§17 Q3','')
add(I,'duplicate-key.json',dump(B).replace(b'"schemaVersion": 1,',b'"schemaVersion": 1,\n  "schemaVersion": 1,'),'parse error with path, B1','parser','§15 OQ-12','')
add(I,'deep-nesting.json',b'{"schemaVersion":1,"pages":'+b'['*100000+b']'*100000+b'}\n','parse error, depth > 8, no StackOverflowError','parser','§15 OQ-9','100k nested arrays')
add(I,'depth-9.json',m(lambda c:c.__setitem__('x-deep',[[[[[[[[1]]]]]]]])),'parse error, depth 9 > 8','parser','§15 OQ-9','x- keys still count toward depth')
add(I,'missing-schemaVersion.json',m(lambda c:c.pop('schemaVersion')),'invalid, B2','both','§15 OQ-3','')
add(I,'newer-schemaVersion.json',m(lambda c:c.__setitem__('schemaVersion',2)),'refused, "needs a newer Config Launcher" banner, never downgrade-written','both','§3 rule 4, §15 OQ-3','')
add(I,'schemaVersion-string.json',m(lambda c:c.__setitem__('schemaVersion','1')),'invalid, B2','both','§15 OQ-3','')
add(I,'missing-pages.json',m(lambda c:c.pop('pages')),'invalid, B2','both','§3','')
add(I,'zero-pages.json',m(lambda c:c.__setitem__('pages',[])),'invalid, B2','both','schema minItems','')
add(I,'21-pages.json',m(lambda c:c.__setitem__('pages',[{'id':f'p{i}','items':[]} for i in range(21)])),'invalid, B2','both','§15 OQ-9','')
add(I,'widget-type.json',m(lambda c:pg(c).append({'id':'w-1','type':'widget','package':'com.android.deskclock','x':3,'y':0})),'invalid, B2','both','§15 OQ-1','')
add(I,'text-type.json',m(lambda c:pg(c).append({'id':'t-1','type':'text','x':3,'y':0})),'invalid, B2','both','§15 OQ-1','')
add(I,'intent-type.json',m(lambda c:pg(c).append({'id':'i-1','type':'intent','package':'com.android.phone','x':3,'y':0})),'invalid, B2; nothing launched','both','§8, §15 OQ-4','')
for k,v in [('action','android.intent.action.CALL'),('uri','tel:911'),('extras',{'k':'v'}),('script','rm -rf /'),('cmd','am start'),('exec','sh')]:
    add(I,f'hostile-key-{k}.json',m(lambda c:pg(c)[0].__setitem__(k,v)),'invalid, B2 (unknown key); no Intent built, nothing launched','both','§8, §15 OQ-4','')
add(I,'unknown-key-typo.json',m(lambda c:pg(c)[0].__setitem__('pakage','x')),'invalid, B2','both','§3, §15 OQ-4','')
for n,pkg in [('intent-uri','intent:#Intent;action=android.intent.action.CALL;end'),('tel-uri','tel:911'),('http-url','http://example.com'),('empty','') ,('dotdot','com..x'),('leading-digit','1com.x'),('slash','com.x/.Main'),('space','com. x'),('no-dot','settings'),('unicode','com.exämple.app')]:
    add(I,f'package-{n}.json',m(lambda c:pg(c)[0].__setitem__('package',pkg)),'invalid, B2; nothing launched','both','§3 schema packageName, spike §3','')
add(I,'package-1000-chars.json',m(lambda c:pg(c)[0].__setitem__('package','com.'+'a'*1000)),'invalid, B2 (256-char cap, now in the schema)','both','§17 Q1','')
add(I,'activity-injection.json',m(lambda c:pg(c)[0].__setitem__('activity','com.x/.Main;S.extra=1')),'invalid, B2','both','schema className','')
add(I,'id-uppercase.json',m(lambda c:pg(c)[0].__setitem__('id','A-Settings')),'invalid, B2','both','§3 Stable IDs','')
add(I,'id-65-chars.json',m(lambda c:pg(c)[0].__setitem__('id','a'*65)),'invalid, B2','both','§15 OQ-9','')
add(I,'duplicate-id.json',m(lambda c:c['dock'][0].__setitem__('id','a-settings')),'invalid, B2','kotlin','§3, §15 OQ-5','')
add(I,'overlapping-cells.json',m(lambda c:pg(c)[1].update(x=0,y=0)),'invalid, B2','kotlin','§15 OQ-2, AQ-3','')
add(I,'cell-outside-grid.json',m(lambda c:pg(c)[0].update(x=4)),'invalid, B2 (grid is 4 wide)','kotlin','§15 OQ-2','')
add(I,'negative-x.json',m(lambda c:pg(c)[0].update(x=-1)),'invalid, B2','both','§15 OQ-2','')
add(I,'grid-13.json',m(lambda c:c.__setitem__('grid',{'columns':13,'rows':5})),'invalid, B2','both','§15 OQ-2','')
add(I,'grid-1.json',m(lambda c:c.__setitem__('grid',{'columns':1,'rows':5})),'invalid, B2','both','§15 OQ-2','')
add(I,'x-float.json',m(lambda c:pg(c)[0].update(x=1.5)),'invalid, B2','both','§15 OQ-2','')
add(I,'x-huge.json',dump(B).replace(b'"x": 0',b'"x": 1e309',1),'invalid, B2/B1; no overflow','both','§15 OQ-2','')
add(I,'page-item-missing-xy.json',m(lambda c:(pg(c)[0].pop('x'))),'invalid, B2','both','§3','')
add(I,'dock-item-with-xy.json',m(lambda c:c['dock'][0].update(x=0,y=0)),'invalid, B2','both','§3','')
add(I,'folder-child-with-xy.json',m(lambda c:pg(c)[2]['items'][0].update(x=0,y=0)),'invalid, B2','both','§3','')
add(I,'nested-folder.json',m(lambda c:pg(c)[2]['items'].append({'id':'f-inner','type':'folder','items':[]})),'invalid, B2','both','§15 OQ-10','')
add(I,'dock-8-items.json',m(lambda c:c.__setitem__('dock',[{'id':f'a-d{i}','type':'app','package':'com.android.settings'} for i in range(8)])),'invalid, B2','both','schema maxItems 7','')
add(I,'label-61-chars.json',m(lambda c:pg(c)[0].__setitem__('label','a'*61)),'invalid, B2','both','§15 OQ-9','')
add(I,'label-1mb.json',m(lambda c:pg(c)[0].__setitem__('label','a'*(200*1024))),'invalid, B2','both','§15 OQ-9','200 KiB label, under file cap')
add(I,'profile-private.json',m(lambda c:pg(c)[0].__setitem__('profile','private')),'invalid, B2','both','§15 OQ-31','')
add(I,'profile-numeric.json',m(lambda c:pg(c)[0].__setitem__('profile',10)),'invalid, B2','both','§15 OQ-29','')
add(I,'revision-negative.json',m(lambda c:c.__setitem__('revision',-1)),'invalid, B2','both','schema','')
# AF-2: `unsorted` is deprecated (accepted, type-checked, ignored; never written). Exactly one VALID fixture keeps the
# block so "accepted and ignored" stays tested; no other generated fixture carries it (#100).
add(V,'unsorted-deprecated.json',m(lambda c:c.__setitem__('unsorted',{'mode':'drawer','pageTitle':'Unsorted'})),'valid; deprecated unsorted block accepted and ignored (apps stay in the drawer; dropped on the next write)','both','A§18.5 AF-2, #100','the only fixture with a valid unsorted block')
add(I,'unsorted-mode-bad.json',m(lambda c:c.__setitem__('unsorted',{'mode':'page2'})),'invalid, B2','both','§15 OQ-25','')
def many(c):
    c['grid']={'columns':12,'rows':12}
    c['pages']=[{'id':f'p{p}','items':[{'id':f'a-{p}-{i}','type':'app','package':'com.android.settings','x':i%12,'y':i//12} for i in range(144)]} for p in range(14)]
add(I,'2016-items.json',(json.dumps(m(many),separators=(',',':'))+"\n").encode(),'invalid, B2 (>2000 items total)','kotlin','§15 OQ-9','14 pages x 144, compact JSON so it stays under 256 KiB')

# ---- v3 additions (ARCHITECTURE §17) ----
CAP = 2*1024*1024
def padded(target, compact=False):
    c = m(lambda c: c.__setitem__('x-pad', ''))
    enc = (lambda o: (json.dumps(o,separators=(',',':'))+"\n").encode()) if compact else dump
    c['x-pad'] = 'a' * (target - len(enc(c)))
    out = enc(c); assert len(out) == target, (len(out), target); return out
add(V,'size-257k.json',padded(257*1024),'valid (under the 2 MiB cap)','store','§17 Q16','was invalid under the old 256 KiB cap')
add(V,'size-just-under-2mib.json',padded(CAP),'valid: exactly 2 MiB (2,097,152 bytes) is accepted','store','§17 Q16','the cap is inclusive: decode rejects size > MAX_BYTES')
add(I,'size-just-over-2mib.json',padded(CAP+1),'refused before full read (COLUMN_SIZE + streaming cap), last-good kept','store','§4 step 1, §17 Q16','2,097,153 bytes')
def longstrings(c, xpad=0):
    pkg = 'com.' + 'a'*96; act = pkg + '.' + 'B'*(151-len(pkg)); label = '\U0001F600'*60
    c['grid']={'columns':12,'rows':12}; c['dock']=[]; c['drawer']={'sort':'label','hidden':[],'rules':[]}
    c['pages']=[]
    n=0
    for p in range(14):
        items=[]
        for i in range(144 if p<13 else 2000-13*144):
            it={'id':(f'a-{p}-{i}-'+'x'*64)[:64],'type':'app','package':pkg,'activity':act,'profile':'personal','label':label,'x':i%12,'y':i//12}
            if xpad: it['x-note']='n'*xpad
            items.append(it)
        c['pages'].append({'id':f'p{p}','items':items})
add(V,'2000-items-long-strings.json',m(longstrings),'valid: 2000 items, 100-char packages, 152-char activities, 60-emoji labels, 64-char IDs (~1.46 MiB canonical)','both','§17 Q16','canonical form; the launcher can read back its own write')
# Readable compact, but over the cap once canonicalised: any edit must be refused by the writer (B5).
add(V,'writer-overflow-seed.json',(json.dumps(m(lambda c: longstrings(c, xpad=330)),ensure_ascii=False,separators=(',',':'))+"\n").encode(),'valid to read (compact, under 2 MiB); after any edit the canonical output is over 2 MiB, so the writer refuses with B5 "launcher.json would be over 2 MB." and keeps the edit in memory','store','§17 Q16','writer-refusal seed (WB-REFUSE)')
# #89/#94 key-order fixture: hand-written layout (compact one-line items, rule keys out of canonical order).
# Emitted as literal bytes so it stays byte-identical to Sprite's original.
KEY_ORDER = (b'{\n  "schemaVersion": 1,\n  "pages": [{ "id": "home", "items": [] }],\n  "drawer": {\n    "rules": [\n'
  b'      { "match": { "category": "game", "tag": "fun", "packagePrefix": "com.games.", "packages": ["com.example.chess"] }, "group": "Games", "id": "r-games" },\n'
  b'      { "group": "Work", "id": "r-work", "match": { "tag": "work" } }\n    ]\n  }\n}\n')
add(V,'drawer-rules-key-order.json',KEY_ORDER,'valid (rule keys rewritten as id, group, match)','both','§20.3','#89')

add(I,'tags-17.json',m(lambda c:pg(c)[0].__setitem__('tags',[f't{i}' for i in range(17)])),'invalid, B2 (tags maxItems 16)','both','§17 Q1','')
add(I,'activity-257-chars.json',m(lambda c:pg(c)[0].__setitem__('activity','a'*257)),'invalid, B2','both','§17 Q1','')
add(I,'hidden-257-chars.json',m(lambda c:c['drawer'].__setitem__('hidden',['com.'+'a'*253])),'invalid, B2','both','§17 Q1','')
add(I,'package-prefix-257-chars.json',m(lambda c:c['drawer']['rules'][0]['match'].update(packagePrefix='a'*257)),'invalid, B2','both','§17 Q1','')
add(I,'match-packages-257-chars.json',m(lambda c:c['drawer']['rules'][0]['match'].update(packages=['com.'+'a'*253])),'invalid, B2','both','§17 Q1','')

add(I,'git-conflict-markers.json',b'{\n  "schemaVersion": 1,\n<<<<<<< HEAD\n  "pages": [{"id": "home", "items": []}]\n=======\n  "pages": [{"id": "work", "items": []}]\n>>>>>>> origin/main\n}\n','parse error, B1; with PR #21: "git merge conflict markers (line 3); finish the merge in git", last-good kept, no write','parser','A§18.1 git primary, CM-21, MG-4','')
# ---- size assertions (#100): every size fixture on the right side of the 2 MiB cap ----
def _size(name, d=V): return os.path.getsize(f'{d}/{name}')
assert _size('size-257k.json') == 257*1024 < CAP
assert _size('size-just-under-2mib.json') == CAP                       # inclusive cap: accepted
assert _size('size-just-over-2mib.json', I) == CAP + 1                 # refused
_li = _size('2000-items-long-strings.json'); assert 1.40*1024*1024 <= _li <= 1.55*1024*1024 and _li < CAP, _li
_seed = open(f'{V}/writer-overflow-seed.json','rb').read()
assert len(_seed) < CAP, len(_seed)                                     # readable
assert len(dump(json.loads(_seed))) > CAP, len(dump(json.loads(_seed))) # canonical (2-space) form exceeds the cap -> B5
assert _size('2016-items.json', I) < 256*1024
SIZES = {'size-257k': _size('size-257k.json'), 'just-under': _size('size-just-under-2mib.json'), 'just-over': _size('size-just-over-2mib.json', I),
         '2000-items': _li, 'seed-compact': len(_seed), 'seed-canonical-approx': len(dump(json.loads(_seed)))}

# #79 elements container (feature level 1) + Stage 1 cell reservation (docs/research/widgets.md §5.1)
def els(*e): return lambda c: c.__setitem__('elements', list(e))
_w = {'id':'w-weather','type':'appWidget','page':'home','x':0,'y':1,'w':2,'h':1,'provider':'com.example.weather/.Widget'}
add(V,'elements-skipped.json',m(lambda c:(els({'id':'t-main','type':'titleBar','anchor':'top','content':{'slots':{'center':'{page}'}}}, _w,
    {'id':'w-new','type':'clock','requires':9,'page':'home','x':3,'y':0,'w':1,'h':1,'future':True})(c),
    c['pages'][0].__setitem__('overrides',{'t-main':{'enabled':False},'t-gone':{'content':{}}}))),
    'valid with 4 warnings: unknown types and requires above the feature level are skipped and kept verbatim; an override for a missing element is ignored','both','title-bar.md §6, widgets.md §5.1','#79')
add(I,'element-overlaps-item.json',m(els(dict(_w, y=0))),'invalid, B2: a grid-placed element (even a skipped one) reserves its cells','kotlin','widgets.md §5.1','#79 Stage 1')
add(I,'element-overlaps-element.json',m(els(_w, dict(_w, id='w-two', x=1))),'invalid, B2: two elements in one cell','kotlin','widgets.md §5.1','#79 Stage 1')
add(I,'element-outside-grid.json',m(els(dict(_w, x=3))),'invalid, B2: x + w past the grid','kotlin','widgets.md §5.1','#79 Stage 1')
add(I,'element-anchor-and-grid.json',m(els(dict(_w, anchor='top'))),'invalid, B2: anchor and a grid position','both','widgets.md §5.1','#79')
add(I,'element-partial-placement.json',m(els({'id':'w-x','type':'appWidget','page':'home','x':0})),'invalid, B2: grid-placed elements need page, x, y, w and h','both','widgets.md §5.1','#79')
add(I,'element-missing-type.json',m(els({'id':'w-x','anchor':'top'})),'invalid, B2','both','title-bar.md §4','#79')
add(I,'override-unknown-key.json',m(lambda c:c['pages'][0].__setitem__('overrides',{'t-main':{'hidden':True}})),'invalid, B2: overrides take enabled, content or style','both','title-bar.md §4','#79')
add(I,'element-duplicate-id.json',m(els(dict(_w, id='a-settings'))),'invalid, B2: element IDs are unique with item IDs','kotlin','title-bar.md §8','#79')

RULES={'duplicate-id.json':'duplicate-ids','overlapping-cells.json':'overlapping-cells','cell-outside-grid.json':'cell-outside-grid','2016-items.json':'item-total','duplicate-key.json':'duplicate-keys','depth-9.json':'parser-depth','deep-nesting.json':'parser-depth','size-just-over-2mib.json':'byte-cap','nul-in-label.json':'nul-or-encoding','invalid-utf8.json':'nul-or-encoding','lone-surrogate.json':'nul-or-encoding','utf16.json':'nul-or-encoding','element-overlaps-item.json':'overlapping-cells','element-overlaps-element.json':'overlapping-cells','element-outside-grid.json':'cell-outside-grid','element-duplicate-id.json':'duplicate-ids'}
for e in man:
    r=RULES.get(e['file'].split('/')[1])
    if r: e['kotlinOnly']=r; e['layer']='kotlin-only'
json.dump(man,open('test-fixtures/configs/manifest.json','w'),indent=2,ensure_ascii=False); open('test-fixtures/configs/manifest.json','a').write('\n')
