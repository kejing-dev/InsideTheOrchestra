"""Prepares the 14 Tripo musician models for the app.

Run:  blender --background --python tools/prep_models.py -- <assets-src dir> <out models dir> <check renders dir>

Input : spatial-app-factory/apps/inside-orchestra/assets-src/<id>.glb (Tripo H3.1, 1.0 unit tall, base on the
        floor, centred, ~4.8 k triangles, three 2K maps)
Output: app/src/main/assets/models/<id>.glb  scaled to real height (metres), origin = floor under the seat
        centre, maps downsized to 1024 JPEG (base colour / roughness-metal / normal), geometry untouched
        <renders>/<id>_front.png               a 256 px view from glTF +Z (the side the app turns to the podium)
"""
import bpy, sys, os, math, mathutils

args = sys.argv[sys.argv.index('--') + 1:]
SRC, OUT, REN = args[0], args[1], args[2]
os.makedirs(OUT, exist_ok=True)
os.makedirs(REN, exist_ok=True)

# real heights (design package assetPipeline.build): seated players incl. chair ~1.25 m,
# the double bass scroll ~1.9 m, the two standing percussionists ~1.60 m
HEIGHT = {
    'violinist': 1.25, 'violist': 1.25, 'cellist': 1.25, 'bassist': 1.90,
    'flutist': 1.25, 'oboist': 1.25, 'clarinetist': 1.25, 'bassoonist': 1.30,
    'hornist': 1.25, 'trumpeter': 1.25, 'trombonist': 1.25, 'tubist': 1.30,
    'timpanist': 1.60, 'bassdrummer': 1.60,
}

# where each raw model faces, degrees from glTF +Z towards +X (multi-angle renders, tools/yaw_check.py);
# the prep turns every model so it faces +Z
FRONT = {'clarinetist': -90, 'flutist': -90, 'hornist': -90, 'trombonist': 90, 'violist': 70, 'cellist': 20}

for f in sorted(os.listdir(SRC)):
    if not f.endswith('.glb'):
        continue
    mid = f[:-4]
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=os.path.join(SRC, f))
    objs = [o for o in bpy.context.scene.objects if o.type == 'MESH']
    s = HEIGHT[mid]
    yaw = -math.radians(FRONT.get(mid, 0))
    for o in objs:
        o.rotation_mode = 'XYZ'
        o.rotation_euler[2] += yaw
        o.scale = (o.scale[0] * s, o.scale[1] * s, o.scale[2] * s)
        o.location = (o.location[0] * s, o.location[1] * s, o.location[2] * s)
    bpy.context.view_layer.update()
    for o in objs:
        bpy.context.view_layer.objects.active = o
        o.select_set(True)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    for img in bpy.data.images:
        if img.size[0] > 1024:
            img.scale(1024, 1024)
    out = os.path.join(OUT, f)
    bpy.ops.export_scene.gltf(filepath=out, export_format='GLB', export_image_format='JPEG',
                              export_jpeg_quality=82, export_yup=True, export_apply=True,
                              export_animations=False, export_cameras=False, export_lights=False)
    print('PREP', mid, 'h', s, 'bytes', os.path.getsize(out))

    # front check: camera on Blender -Y (= glTF +Z) looking at the model
    scn = bpy.context.scene
    cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam'))
    scn.collection.objects.link(cam)
    cam.location = (0, -4.0, s * 0.55)
    cam.rotation_euler = (math.radians(90), 0, 0)
    cam.data.lens = 50 if s < 1.5 else 38
    scn.camera = cam
    sun = bpy.data.objects.new('sun', bpy.data.lights.new('sun', 'SUN'))
    sun.rotation_euler = (math.radians(50), 0, math.radians(20))
    scn.collection.objects.link(sun)
    scn.world = bpy.data.worlds.new('w')
    scn.world.color = (0.6, 0.6, 0.6)
    scn.render.engine = 'BLENDER_EEVEE' if 'BLENDER_EEVEE' in [e.identifier for e in bpy.types.RenderSettings.bl_rna.properties['engine'].enum_items] else 'BLENDER_EEVEE_NEXT'
    scn.render.resolution_x = scn.render.resolution_y = 256
    scn.render.filepath = os.path.join(REN, mid + '_front.png')
    bpy.ops.render.render(write_still=True)
