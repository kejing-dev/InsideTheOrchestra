import bpy, sys, os, math
args = sys.argv[sys.argv.index('--') + 1:]
SRC, REN = args[0], args[1]
for mid in args[2].split(','):
    for k, ang in enumerate([0, 45, -45, 90, -90]):
        bpy.ops.wm.read_factory_settings(use_empty=True)
        bpy.ops.import_scene.gltf(filepath=os.path.join(SRC, mid + '.glb'))
        scn = bpy.context.scene
        a = math.radians(ang)
        # camera orbit: ang>0 = camera towards glTF +X (Blender +X)
        cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam')); scn.collection.objects.link(cam)
        cam.location = (4 * math.sin(a), -4 * math.cos(a), 0.65)
        cam.rotation_euler = (math.radians(90), 0, a)
        cam.data.lens = 60; scn.camera = cam
        sun = bpy.data.objects.new('sun', bpy.data.lights.new('sun', 'SUN')); sun.rotation_euler = (math.radians(40), 0, a); scn.collection.objects.link(sun)
        scn.world = bpy.data.worlds.new('w'); scn.world.color = (0.6, 0.6, 0.6)
        scn.render.engine = 'BLENDER_EEVEE'
        scn.render.resolution_x = scn.render.resolution_y = 200
        scn.render.filepath = os.path.join(REN, f'{mid}_{k}.png')
        bpy.ops.render.render(write_still=True)
