from PIL import Image, ImageDraw
import os

def make_icon(size):
    s = size
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # red rounded rect, margin 2%
    m = max(1, round(s * 0.02))
    radius = round(s * 0.22)
    d.rounded_rectangle([m, m, s - m - 1, s - m - 1], radius=radius, fill=(255, 0, 0, 255))
    # white play triangle centered, slight right offset for optical centering
    tw = round(s * 0.38)   # triangle width
    th = round(s * 0.42)   # triangle height
    cx, cy = s / 2, s / 2
    left = cx - tw / 2 + s * 0.02
    top = cy - th / 2
    d.polygon([(left, top), (left, top + th), (left + tw, cy)], fill=(255, 255, 255, 255))
    return img

base = r"C:/Users/fengxin/WorkBuddy/悠兔/out/decompile/proj2/res"
targets = [
    (os.path.join(base, "drawable-hdpi", "ic_launcher.png"), 72),
    (os.path.join(base, "drawable-xxhdpi", "ic_launcher1.png"), 144),
]
for path, size in targets:
    make_icon(size).save(path)
    print("wrote", path, size)
