"""Snapshot selected pack/mod art into the standalone wiki; render model icons offline.
Run manually after changing assets: python code/tools/import-wiki-media.py
Normal documentation builds read the snapshot and do not depend on pack/.
"""
import hashlib,json,math,shutil
from pathlib import Path
import numpy as np
from PIL import Image,ImageEnhance

ROOT=Path(__file__).resolve().parents[2]
DEST=ROOT/'wiki/assets/images'
CONTENT=ROOT/'wiki/content'
SOURCES={'minecraft':ROOT/'pack/assets/minecraft','contribution':ROOT/'code/src/main/resources/assets/contribution'}
RES=ROOT/'code/src/main/resources/data/contribution'
DEST.mkdir(parents=True,exist_ok=True)
provenance={};icons={};models={};missing={}

def split(id):return id.split(':',1) if ':' in id else ('minecraft',id)
def source(id,kind,suffix):
 ns,key=split(id);return SOURCES[ns]/kind/(key+suffix)
def copied(file):
 file=file.resolve(); ns=next(ns for ns,base in SOURCES.items() if file.is_relative_to(base.resolve()))
 rel=file.relative_to(SOURCES[ns].resolve());out=DEST/'source'/ns/rel
 out.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(file,out)
 key=out.relative_to(ROOT/'wiki').as_posix()
 provenance[key]={'source':file.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(file.read_bytes()).hexdigest()}
 return out

def load(id):
 if id in models:return models[id]
 file=source(id,'models','.json');copied(file);data=json.loads(file.read_text(encoding='utf-8'))
 parent=data.get('parent');base={}
 if parent and parent not in ('minecraft:builtin/generated','builtin/generated','minecraft:builtin/entity','builtin/entity'):
  base=load(parent)
 merged={**base,**data,'textures':{**base.get('textures',{}),**data.get('textures',{})},'display':{**base.get('display',{}),**data.get('display',{})}}
 models[id]=merged;return merged

def texture(ref,textures):
 seen=set()
 while isinstance(ref,str) and ref.startswith('#'):
  if ref in seen:raise ValueError('Cyclic texture '+ref)
  seen.add(ref);ref=textures[ref[1:]]
 if isinstance(ref,dict):ref=ref['sprite']
 file=source(ref,'textures','.png');copied(file)
 im=Image.open(file).convert('RGBA')
 # Animated textures show the first frame in the static article.
 meta=file.with_suffix('.png.mcmeta')
 if meta.exists():
  info=json.loads(meta.read_text(encoding='utf-8')).get('animation',{})
  w=info.get('width',im.width);h=info.get('height',w)
  im=im.crop((0,0,w,h));copied(meta)
 return im

def choose(node):
 t=node.get('type','').removeprefix('minecraft:')
 if t in ('model','special'):return node
 if t=='condition':return choose(node['on_false'])
 if t=='range_dispatch':return choose(node.get('fallback',node['entries'][0]['model']))
 if t=='composite':return choose(node['models'][0])
 if t=='select':
  if node.get('property')=='minecraft:display_context':
   for c in node.get('cases',[]):
    if 'gui' in (c['when'] if isinstance(c['when'],list) else [c['when']]):return choose(c['model'])
  return choose(node.get('fallback',node.get('cases',[{}])[0].get('model')))
 raise ValueError('Unsupported item selector '+str(node))

def rotation(axis,angle):
 a=math.radians(angle);c=math.cos(a);s=math.sin(a)
 if axis=='x':return np.array([[1,0,0],[0,c,-s],[0,s,c]])
 if axis=='y':return np.array([[c,0,s],[0,1,0],[-s,0,c]])
 return np.array([[c,-s,0],[s,c,0],[0,0,1]])

def geometry(data):
 textures=data.get('textures',{});polygons=[]
 gui=data.get('display',{}).get('gui',{})
 angles=gui.get('rotation',[30,225,0])
 matrix=rotation('z',angles[2])@rotation('x',angles[0])@rotation('y',angles[1])
 for elem in data.get('elements',[]):
  x,y,z=elem['from'];X,Y,Z=elem['to']
  # Vertices correspond to texture TL, TR, BR, BL on each face.
  faces={'south':[(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],'north':[(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],'east':[(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],'west':[(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],'up':[(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)],'down':[(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)]}
  defaults={'south':[x,16-Y,X,16-y],'north':[16-X,16-Y,16-x,16-y],'east':[16-Z,16-Y,16-z,16-y],'west':[z,16-Y,Z,16-y],'up':[x,z,X,Z],'down':[x,16-Z,X,16-z]}
  for face,attrs in elem.get('faces',{}).items():
   pts=np.array(faces[face],dtype=float)
   if 'rotation' in elem:
    r=elem['rotation'];origin=np.array(r.get('origin',[8,8,8]));axis=r['axis'];rot=rotation(axis,r['angle'])
    pts=(pts-origin)@rot.T
    if r.get('rescale'):
     scale=np.ones(3)/max(math.cos(math.radians(r['angle'])),.01);scale['xyz'.index(axis)]=1;pts*=scale
    pts+=origin
   pts=(pts-np.array([8,8,8]))@matrix.T
   normal=np.cross(pts[1]-pts[0],pts[3]-pts[0])
   if normal[2]>=-1e-6:continue
   im=texture(attrs['texture'],textures);uv=attrs.get('uv',defaults[face]);u,v,U,V=uv
   # Preserve flipped UVs and face rotation.
   crop=im.crop((round(min(u,U)*im.width/16),round(min(v,V)*im.height/16),round(max(u,U)*im.width/16),round(max(v,V)*im.height/16)))
   if crop.width==0 or crop.height==0:continue
   if u>U:crop=crop.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
   if v>V:crop=crop.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
   if attrs.get('rotation'):crop=crop.rotate(-attrs['rotation'],expand=True)
   norm=normal/np.linalg.norm(normal);light=.64+.36*max(0,float(norm@np.array([-.35,.6,-.72])))
   if elem.get('shade',True):crop=ImageEnhance.Brightness(crop).enhance(light)
   polygons.append((float(pts[:,2].mean()),pts[:,[0,1]]*np.array([1,-1]),crop))
 if not polygons:raise ValueError('Model has no visible faces')
 allpts=np.concatenate([p[1] for p in polygons]);low=allpts.min(axis=0);high=allpts.max(axis=0)
 scale=112/max(high-low);center=(low+high)/2
 canvas=Image.new('RGBA',(128,128))
 for _,pts,im in sorted(polygons,key=lambda p:p[0],reverse=True):
  pts=(pts-center)*scale+64
  a=(pts[1]-pts[0])/im.width;b=(pts[3]-pts[0])/im.height
  M=np.column_stack([a,b]);inv=np.linalg.inv(M);off=-inv@pts[0]
  coeff=(inv[0,0],inv[0,1],off[0],inv[1,0],inv[1,1],off[1])
  layer=im.transform((128,128),Image.Transform.AFFINE,coeff,Image.Resampling.NEAREST)
  canvas=Image.alpha_composite(canvas,layer)
 return canvas

def special(node):
 t=node['model']['type'].removeprefix('minecraft:')
 if t=='chest':
  # Vanilla single chest atlas: lid, body and latch, excluding seasonal substitution.
  textures={'atlas':'minecraft:entity/chest/normal'}
  def box(lo,hi,uv):return {'from':lo,'to':hi,'faces':{f:{'texture':'#atlas','uv':[x/4 for x in u]} for f,u in uv.items()}}
  return geometry({'textures':textures,'elements':[
   box([1,0,1],[15,10,15],{'south':[14,33,28,43],'north':[42,33,56,43],'west':[0,33,14,43],'east':[28,33,42,43],'up':[14,19,28,33]}),
   box([1,10,1],[15,14,15],{'south':[14,14,28,19],'north':[42,14,56,19],'west':[0,14,14,19],'east':[28,14,42,19],'up':[14,0,28,14]}),
   box([7,8,15],[9,12,16],{'south':[1,1,3,5],'west':[0,1,1,5],'east':[3,1,4,5],'up':[1,0,3,1]})]})
 if t=='shield':
  atlas='minecraft:entity/shield/shield_base_nopattern'
  texture(atlas,{})
  faces={'north':[2,1,14,23],'south':[16,1,28,23],'west':[0,1,2,23],'east':[14,1,16,23],'up':[2,0,14,1]}
  return geometry({'display':load(node['base']).get('display',{}),'textures':{'atlas':atlas},'elements':[{'from':[2,-3,7],'to':[14,19,9],'faces':{f:{'texture':'#atlas','uv':[v/4 for v in uv]} for f,uv in faces.items()}}]})
 if t=='decorated_pot':
  side='minecraft:entity/decorated_pot/decorated_pot_side';base='minecraft:entity/decorated_pot/decorated_pot_base'
  return geometry({'textures':{'side':side,'base':base},'elements':[
   {'from':[1,0,1],'to':[15,16,15],'faces':{f:{'texture':'#side','uv':[0,0,16,16]} for f in ['north','south','west','east']}},
   {'from':[1,16,1],'to':[15,16.1,15],'faces':{'up':{'texture':'#base','uv':[0,0,7,7]}}},
   {'from':[5,16,5],'to':[11,18,11],'faces':{f:{'texture':'#base','uv':[0,7,3,8]} for f in ['north','south','west','east']}},
   {'from':[4,18,4],'to':[12,19,12],'faces':{f:{'texture':'#base','uv':[0,8,4,8.5]} for f in ['north','south','west','east']}},
   {'from':[4,19,4],'to':[12,19.1,12],'faces':{'up':{'texture':'#base','uv':[0,8.5,4,12.5]}}}]})
 if t in ('player_head','head'):
  kind=node['model'].get('kind','player')
  refs={'player':'entity/player/wide/steve','skeleton':'entity/skeleton/skeleton','wither_skeleton':'entity/skeleton/wither_skeleton','zombie':'entity/zombie/zombie','creeper':'entity/creeper/creeper','piglin':'entity/piglin/piglin','dragon':'entity/enderdragon/dragon'}
  ref='minecraft:'+refs[kind];im=texture(ref,{})
  w=im.width/16;h=im.height/16
  uv={'south':[8/w,8/h,16/w,16/h],'north':[24/w,8/h,32/w,16/h],'west':[0,8/h,8/w,16/h],'east':[16/w,8/h,24/w,16/h],'up':[8/w,0,16/w,8/h]}
  return geometry({'textures':{'head':ref},'elements':[{'from':[4,4,4],'to':[12,12,12],'faces':{f:{'texture':'#head','uv':u} for f,u in uv.items()}}]})
 raise ValueError('Unsupported special renderer '+t)

def render_node(node):
 node=choose(node)
 if node['type']=='minecraft:special':return special(node),'model'
 data=load(node['model']);layers=[(k,v) for k,v in data.get('textures',{}).items() if k.startswith('layer')]
 if layers and not data.get('elements'):
  canvas=Image.new('RGBA',(128,128))
  for key,ref in sorted(layers):
   im=texture(ref,data['textures']);n=int(key[5:]);tints=node.get('tints',[])
   if n<len(tints):
    tint=tints[n].get('default',tints[n].get('value',0xffffff))&0xffffff
    a=np.array(im);a[:,:,:3]=(a[:,:,:3].astype(float)*np.array([tint>>16,(tint>>8)&255,tint&255])/255).astype('uint8');im=Image.fromarray(a)
   im=im.resize((112,112),Image.Resampling.NEAREST);canvas.alpha_composite(im,(8,8))
  return canvas,'sprite'
 return geometry(data),'model'

def stackkey(stack):
 if isinstance(stack,str):return 'minecraft:oak_log' if stack=='#minecraft:logs' else stack
 if isinstance(stack,list):return stackkey(stack[0])
 comp=stack.get('components',{});return 'model:'+comp['minecraft:item_model'] if comp.get('minecraft:item_model') else stackkey(stack.get('id',stack.get('item',stack.get('items',''))))

def register(key,node=None):
 if key in icons:return
 try:
  if node is None:
   itemkey=key.removeprefix('model:');file=source(itemkey,'items','.json');copied(file)
   node=json.loads(file.read_text(encoding='utf-8'))['model']
  image,kind=render_node(node)
  name=key.replace(':','-').replace('/','-');out=DEST/'icons'/(name+'.png');out.parent.mkdir(parents=True,exist_ok=True);image.save(out)
  icons[key]={'src':out.relative_to(ROOT/'wiki').as_posix(),'kind':kind,'width':128,'height':128}
 except (KeyError,FileNotFoundError,ValueError) as e:missing[key]=str(e)

requests=set()
for file in (RES/'recipe/items').rglob('*.json'):
 data=json.loads(file.read_text(encoding='utf-8'))
 requests.add(stackkey(data['result']))
 for k in ['template','base','addition','input','material']:
  if k in data:requests.add(stackkey(data[k]))
 for value in data.get('key',{}).values():requests.add(stackkey(value))
for file in (RES/'advancement').rglob('*.json'):
 data=json.loads(file.read_text(encoding='utf-8'))
 if data.get('display'):requests.add(stackkey(data['display']['icon']))
requests.update('minecraft:'+key for key in ['emerald','gold_ingot','diamond','paper','book','writable_book','clock','compass','chest','crafting_table','smithing_table','anvil','iron_pickaxe','diamond_pickaxe','wheat','redstone','bread','torch','ender_pearl','nether_star','experience_bottle','enchanted_book','barrier','recovery_compass','map','golden_spear','copper_ingot','brewing_stand','minecart','oak_boat'])
for key in sorted(requests):register(key)
skins={}
for family in ['sword','axe','pickaxe','hoe','shovel','spear']:
 file=SOURCES['contribution']/f'items/weapon_skin/{family}.json';copied(file)
 definition=json.loads(file.read_text(encoding='utf-8'))
 register('model:contribution:weapon_skin/'+family)
 skins[family]=[]
 for entry in definition['model']['cases']:
  for label in entry['when'] if isinstance(entry['when'],list) else [entry['when']]:
   key='skin:'+family+'/'+label;register(key,entry['model']);skins[family].append({'key':key,'label':label})
# Source inventory records exactly which originals were copied. Browser paths are wiki-local.
manifest={'schemaVersion':1,'origin':'User-extracted Minecraft 26.3 pack and current contribution mod assets','icons':icons,'skins':skins,'sources':provenance,'unresolved':missing}
(CONTENT/'media.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'icons':len(icons),'sourceFiles':len(provenance),'unresolved':missing},ensure_ascii=False,indent=2))
