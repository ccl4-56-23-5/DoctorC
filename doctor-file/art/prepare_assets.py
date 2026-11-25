"""Package ImageGen originals into Android PNG sizes; no icon geometry is drawn here."""
from pathlib import Path
from PIL import Image
import hashlib,json
root=Path(__file__).resolve().parents[1]
originals=root/'art/generated'
res=root/'app/src/native/res'
target=res/'drawable-nodpi';target.mkdir(exist_ok=True)
records=[]
for path in sorted(originals.glob('*.png')):
    im=Image.open(path).convert('RGBA')
    if path.stem!='launcher':
        size=96 if path.stem=='notification' else 192
        im.resize((size,size),Image.Resampling.LANCZOS).save(target/f'art_{path.stem}.png',optimize=True)
        alpha=im.getchannel('A')
        records.append({'name':path.stem,'original_size':im.size,'alpha_range':alpha.getextrema(),'corner_alpha':[alpha.getpixel((x,y)) for x,y in [(0,0),(im.width-1,0),(0,im.height-1),(im.width-1,im.height-1)]],
                        'sha256':hashlib.sha256(path.read_bytes()).hexdigest().upper(),'packaged_size':size})
logo=Image.open(originals/'launcher.png').convert('RGBA')
mono=Image.open(originals/'notification.png').convert('RGBA')
for density,legacy,adaptive in [('mdpi',48,108),('hdpi',72,162),('xhdpi',96,216),('xxhdpi',144,324),('xxxhdpi',192,432)]:
    folder=res/f'mipmap-{density}';folder.mkdir(exist_ok=True)
    logo.resize((legacy,legacy),Image.Resampling.LANCZOS).save(folder/'ic_launcher.png',optimize=True)
    logo.resize((adaptive,adaptive),Image.Resampling.LANCZOS).save(folder/'ic_launcher_foreground.png',optimize=True)
    # The adaptive foreground contains the complete generated square design.
    logo.resize((adaptive,adaptive),Image.Resampling.LANCZOS).save(folder/'ic_launcher_background.png',optimize=True)
    mono.resize((adaptive,adaptive),Image.Resampling.LANCZOS).save(folder/'ic_launcher_monochrome.png',optimize=True)
(root/'art/asset-check.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'packaged_icons':len(records),'transparent_corners_fail':[r['name'] for r in records if any(r['corner_alpha'])]},ensure_ascii=False))
