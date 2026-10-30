"""Renders the 23 seats with the prepared models (assets/models) as the app places them: a check of scale, facing
(every musician turned to the podium) and spacing, seen from the audience 3 m in front of the podium.
Run: blender --background --python tools/layout_check.py -- <out.png>"""
import bpy, sys, os, math
out = sys.argv[sys.argv.index('--') + 1]
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'io', 'embodit', 'insideorchestra', 'domain', 'model', 'Orchestra.kt')
import re
seats = re.findall(r'Seat\("(\w+)", "[^"]*", "[^"]*", Part\.\w+, Stem\.\w+, (-?[0-9.]+)f, (-?[0-9.]+)f, "(\w+)"', open(SRC, encoding='utf-8').read())
bpy.ops.wm.read_factory_settings(use_empty=True)
scn = bpy.context.scene
cache = {}
for sid, x, z, model in seats:
    x, z = float(x), float(z)
    before = set(bpy.context.scene.objects)
    bpy.ops.import_scene.gltf(filepath=os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'models', model + '.glb'))
    new = [o for o in bpy.context.scene.objects if o not in before]
    yaw = math.atan2(-x, -z)  # glTF yaw about +Y == Blender yaw about +Z
    for o in new:
        if o.parent is None:
            o.rotation_mode = 'XYZ'
            o.rotation_euler[2] += yaw
            # glTF (x, y, z) -> Blender (x, -z, y)
            o.location = (x, -z, 0)
bpy.ops.mesh.primitive_cylinder_add(radius=0.35, depth=0.12, location=(0, 0, 0.06))
cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam')); scn.collection.objects.link(cam)
cam.location = (0.2, -3.2, 2.6)  # audience 3 m in front (+Z glTF = -Y Blender)
cam.rotation_euler = (math.radians(62), 0, 0)
cam.data.lens = 18
scn.camera = cam
sun = bpy.data.objects.new('sun', bpy.data.lights.new('sun', 'SUN')); sun.rotation_euler = (math.radians(40), 0, math.radians(15)); scn.collection.objects.link(sun)
scn.world = bpy.data.worlds.new('w'); scn.world.color = (0.55, 0.55, 0.58)
scn.render.engine = 'BLENDER_EEVEE'
scn.render.resolution_x, scn.render.resolution_y = 1400, 800
scn.render.filepath = out
bpy.ops.render.render(write_still=True)
print('LAYOUT', len(seats), out)
