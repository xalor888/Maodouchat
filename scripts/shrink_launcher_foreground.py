#!/usr/bin/env python3
"""将精调版启动图标前景等比缩小到自适应图标安全区内。

背景：仓库中的 ic_launcher_foreground.png 是手工定稿构图，不能用浏览器
渲染等方式重新生成（会改变构图与观感）。
此脚本只做一件事：把定稿前景图整体等比缩小，使内容宽度占画布约 55%
（安全区为 66/108 ≈ 61%），构图、配色、抗锯齿全部保留原样。
"""
from PIL import Image

TARGET_RATIO = 0.55  # 内容宽度占画布比例

FOREGROUNDS = [
    'app/src/main/res/drawable-mdpi/ic_launcher_foreground.png',
    'app/src/main/res/drawable-hdpi/ic_launcher_foreground.png',
    'app/src/main/res/drawable-xhdpi/ic_launcher_foreground.png',
    'app/src/main/res/drawable-xxhdpi/ic_launcher_foreground.png',
    'app/src/main/res/drawable-xxxhdpi/ic_launcher_foreground.png',
]

for rel_path in FOREGROUNDS:
    img = Image.open(rel_path).convert('RGBA')
    size = img.width
    bbox = img.getbbox()
    if bbox is None:
        raise SystemExit(f'{rel_path}: 空图像')
    content_w = bbox[2] - bbox[0]
    target_w = TARGET_RATIO * size
    k = target_w / content_w
    new_dim = round(size * k)
    scaled = img.resize((new_dim, new_dim), Image.LANCZOS)
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    offset = (size - new_dim) // 2
    canvas.paste(scaled, (offset, offset))
    canvas.save(rel_path)
    print(f'{rel_path}: 画布 {size}px, 内容宽 {content_w}px -> {round(target_w)}px '
          f'(缩放 {k:.3f})')

print('完成')
