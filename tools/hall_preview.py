"""Preview of the VR hall with the orchestra (blender --background --python tools/hall_preview.py -- <out.png> [cam x y z pitch_deg lens])."""
import bpy, sys, os, math, re
a = sys.argv[sys.argv.index('--') + 1:]
out = a[0]
cx, cy, cz, pitch, lens = (float(v) for v in (a[1:6] if len(a) >= 6 else (0.3, 1.6, 3.2, 84, 14)))
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
M = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'models')
bpy.ops.wm.read_factory_settings(use_empty=True)
scn = bpy.context.scene
G = lambda x, y, z: (x, -z, y)
bpy.ops.import_scene.gltf(filepath=os.path.join(M, 'hall.glb'))
src = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'io', 'embodit', 'insideorchestra', 'domain', 'model', 'Orchestra.kt')
seats = re.findall(r'Seat\("(\w+)", "[^"]*", "[^"]*", Part\.\w+, Stem\.\w+, (-?[0-9.]+)f, (-?[0-9.]+)f, "(\w+)"', open(src, encoding='utf-8').read())
for sid, x, z, model in seats:
    x, z = float(x), float(z)
    before = set(scn.objects)
    bpy.ops.import_scene.gltf(filepath=os.path.join(M, model + '.glb'))
    for o in [o for o in scn.objects if o not in before and o.parent is None]:
        o.rotation_euler[2] += math.atan2(-x, -z)
        o.location = (x, -z, 0)
for (lx, lz) in ((-3, -1.5), (0, -2.5), (3, -1.5)):
    sp = bpy.data.objects.new('spot', bpy.data.lights.new('spot', 'SPOT'))
    sp.data.energy = 2600; sp.data.color = (1.0, 0.86, 0.68); sp.data.spot_size = math.radians(70)
    sp.location = G(lx, 8.0, lz + 2.5); sp.rotation_euler = (math.radians(20), 0, 0)
    scn.collection.objects.link(sp)
fill = bpy.data.objects.new('fill', bpy.data.lights.new('fill', 'AREA'))
fill.data.energy = 900; fill.data.size = 12; fill.data.color = (1.0, 0.8, 0.6); fill.location = G(0, 10.5, 8)
scn.collection.objects.link(fill)
cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam')); scn.collection.objects.link(cam)
cam.location = G(cx, cy, cz); cam.rotation_euler = (math.radians(pitch), 0, math.radians(float(a[6]) if len(a) > 6 else 0)); cam.data.lens = lens
scn.camera = cam
scn.world = bpy.data.worlds.new('w'); scn.world.color = (0.02, 0.015, 0.012)
scn.render.engine = 'CYCLES'
scn.cycles.device = 'CPU'
scn.cycles.samples = 24
scn.cycles.use_denoising = True
scn.render.resolution_x, scn.render.resolution_y = 1200, 675
scn.render.filepath = out
bpy.ops.render.render(write_still=True)
print('PREVIEW', out)
