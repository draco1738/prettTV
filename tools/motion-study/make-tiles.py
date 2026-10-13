#!/usr/bin/env python3
"""Use your own tile artwork in the motion study.

    tiles/favs/1-films.jpg, tiles/favs/2-series.jpg, ...    large tiles, 400x225 (16:9)
    tiles/grid/01-news.jpg, ...                             small tiles, any 16:9 size
    python3 make-tiles.py                                   writes tiles.js next to index.html

Files are taken in name order; the label is the file name without the leading number.
JPEG, PNG and WebP work. tiles/ and tiles.js are git-ignored: app banners belong to their owners.
"""
import base64, json, mimetypes, os, re

here = os.path.dirname(os.path.abspath(__file__))

def row(kind):
    d = os.path.join(here, 'tiles', kind)
    out = []
    for name in sorted(os.listdir(d)) if os.path.isdir(d) else []:
        mime = mimetypes.guess_type(name)[0] or ''
        if not mime.startswith('image/'): continue
        label = re.sub(r'^\d+[-_ ]*', '', os.path.splitext(name)[0]).replace('_', ' ').replace('-', ' ').strip() or name
        data = base64.b64encode(open(os.path.join(d, name), 'rb').read()).decode()
        out.append({'id': re.sub(r'\W+', '', label.lower()), 'label': label[:1].upper() + label[1:], 'img': f'data:{mime};base64,{data}'})
    return out

favs, grid = row('favs'), row('grid')
if not favs or not grid: raise SystemExit('put images in tiles/favs and tiles/grid first (see the top of this file)')
with open(os.path.join(here, 'tiles.js'), 'w') as f: f.write('window.TILES = ' + json.dumps({'favs': favs, 'grid': grid}) + ';\n')
print(f'tiles.js: {len(favs)} favourites, {len(grid)} grid tiles')
