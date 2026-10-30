"""The VR concert hall around the orchestra (v0.1.4; user: "VR 模式需要把场景做好看").

Run:  blender --background --python tools/make_hall.py -- <textures dir> <out glb> [<preview png>]

Coordinates (glTF, metres) = orchestra coordinates: origin = podium centre on the floor, +Z towards the audience,
musicians at z 0 .. -3.65. The hall floor is y = 0, the same plane the orchestra stands on in MR (the app puts the
hall under the orchestra root's pose). Pieces:
  stage            honey planks, x -8 .. 8, z -7 .. +2.2, with a brass nosing line at the front edge
  acoustic shell   curved light-wood panels behind the orchestra (arc r 9.5 around (0, 0, 2)), 7.5 m high
  side walls       x = +-10.5: dark wood wainscot to 1.2 m, red fabric above, cream pilasters every 3 m,
                   warm sconces, a balcony at 4.5 m with a cream moulded front
  back wall        z = 18 with a balcony
  audience         carpet floor z 2.2 .. 18, 12 rows of red velvet seats from z 5 (aisle in the middle)
  ceiling          coffered at 11.5 m, rows of warm recessed lamps (emissive)
Lamps / sconces are emissive (they read as light sources whatever the engine lights do); the app adds warm spot
lights over the stage in VR.
"""
import bpy, bmesh, sys, os, math

a = sys.argv[sys.argv.index('--') + 1:]
TEX, OUT = a[0], a[1]
PREVIEW = a[2] if len(a) > 2 else None
bpy.ops.wm.read_factory_settings(use_empty=True)
scn = bpy.context.scene


def G(x, y, z):
    """glTF (x, y up, z) -> Blender (x, -z, y)."""
    return (x, -z, y)


def mat(name, tex=None, color=(1, 1, 1), rough=0.6, metal=0.0, emit=None, emit_strength=0.0, scale=(1, 1), glow=0.22):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    b = nt.nodes['Principled BSDF']
    b.inputs['Roughness'].default_value = rough
    b.inputs['Metallic'].default_value = metal
    if tex:
        img = bpy.data.images.load(os.path.join(TEX, tex))
        t = nt.nodes.new('ShaderNodeTexImage')
        t.image = img
        # tiling goes into the uv themselves (UV_SCALE): no KHR_texture_transform in the GLB, the engine may not read it
        UV_SCALE[name] = scale
        nt.links.new(t.outputs['Color'], b.inputs['Base Color'])
        if glow > 0:
            # self-lit share: the hall never goes black whatever the engine lights do (Mixed Stage keeps the real IBL)
            nt.links.new(t.outputs['Color'], b.inputs['Emission Color'])
            b.inputs['Emission Strength'].default_value = glow
    else:
        b.inputs['Base Color'].default_value = (*color, 1)
    if emit:
        b.inputs['Emission Color'].default_value = (*emit, 1)
        b.inputs['Emission Strength'].default_value = emit_strength
    return m


UV_SCALE = {}

M = {
    'stage': mat('stage', 'hall_stage.jpg', rough=0.45, scale=(4, 4)),
    'wainscot': mat('wainscot', 'hall_wainscot.jpg', rough=0.5, scale=(6, 1)),
    'shell': mat('shell', 'hall_shell.jpg', rough=0.5),
    'fabric': mat('fabric', 'hall_fabric.jpg', rough=0.9, scale=(6, 3)),
    'carpet': mat('carpet', 'hall_carpet.jpg', rough=0.95, scale=(6, 6)),
    'velvet': mat('velvet', 'hall_velvet.jpg', rough=0.85),
    'plaster': mat('plaster', 'hall_plaster.jpg', rough=0.7),
    'ceiling': mat('ceiling', 'hall_ceiling.jpg', rough=0.8, scale=(6, 6)),
    'brass': mat('brass', color=(0.78, 0.6, 0.3), rough=0.3, metal=0.9),
    'lamp': mat('lamp', color=(1.0, 0.85, 0.6), emit=(1.0, 0.82, 0.55), emit_strength=6.0),
    'sconce': mat('sconce', color=(1.0, 0.8, 0.5), emit=(1.0, 0.75, 0.45), emit_strength=4.0),
}


class Builder:
    """One mesh per material, built with bmesh and uv set per face (box-projected, metres / tile)."""

    def __init__(self):
        self.bms = {}

    def bm(self, key):
        if key not in self.bms:
            self.bms[key] = bmesh.new()
        return self.bms[key]

    def quad(self, key, p0, p1, p2, p3, uv=None):
        bm = self.bm(key)
        vs = [bm.verts.new(G(*p)) for p in (p0, p1, p2, p3)]
        f = bm.faces.new(vs)
        lay = bm.loops.layers.uv.verify()
        uvs = uv or [(0, 0), (1, 0), (1, 1), (0, 1)]
        su, sv = UV_SCALE.get(key, (1, 1))
        for loop, t in zip(f.loops, uvs):
            loop[lay].uv = (t[0] * su, t[1] * sv)
        return f

    def box(self, key, lo, hi, tile=1.0):
        x0, y0, z0 = lo
        x1, y1, z1 = hi
        w, h, d = (x1 - x0) / tile, (y1 - y0) / tile, (z1 - z0) / tile
        q = self.quad
        q(key, (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1), [(0, 0), (w, 0), (w, h), (0, h)])  # +z
        q(key, (x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0), [(0, 0), (w, 0), (w, h), (0, h)])  # -z
        q(key, (x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1), [(0, 0), (d, 0), (d, h), (0, h)])  # +x
        q(key, (x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0), [(0, 0), (d, 0), (d, h), (0, h)])  # -x
        q(key, (x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0), [(0, 0), (w, 0), (w, d), (0, d)])  # top
        q(key, (x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), [(0, 0), (w, 0), (w, d), (0, d)])  # bottom

    def finish(self):
        for key, bm in self.bms.items():
            me = bpy.data.meshes.new('hall_' + key)
            bm.normal_update()
            bm.to_mesh(me)
            bm.free()
            ob = bpy.data.objects.new('hall_' + key, me)
            ob.data.materials.append(M[key])
            scn.collection.objects.link(ob)


B = Builder()
STAGE_FRONT = 2.2
# floors (y = 0: the orchestra's floor)
B.quad('stage', (-8, 0.002, STAGE_FRONT), (8, 0.002, STAGE_FRONT), (8, 0.002, -7.5), (-8, 0.002, -7.5), [(0, 0), (4, 0), (4, 2.4), (0, 2.4)])
B.box('brass', (-8, 0.0, STAGE_FRONT - 0.04), (8, 0.012, STAGE_FRONT))
B.quad('carpet', (-10.5, 0.001, 18), (10.5, 0.001, 18), (10.5, 0.001, STAGE_FRONT), (-10.5, 0.001, STAGE_FRONT), [(0, 0), (3, 0), (3, 2.3), (0, 2.3)])
B.quad('wainscot', (-10.5, 0.0015, STAGE_FRONT), (-8, 0.0015, STAGE_FRONT), (-8, 0.0015, -7.5), (-10.5, 0.0015, -7.5), [(0, 0), (1, 0), (1, 4), (0, 4)])
B.quad('wainscot', (8, 0.0015, STAGE_FRONT), (10.5, 0.0015, STAGE_FRONT), (10.5, 0.0015, -7.5), (8, 0.0015, -7.5), [(0, 0), (1, 0), (1, 4), (0, 4)])

# acoustic shell: 15 tall slatted panels on an arc around (0, 0, 2), each turned to the centre
R, CZ, H = 9.5, 2.0, 7.5
n = 15
for i in range(n):
    a0 = math.radians(-58 + 116 * i / n)
    a1 = math.radians(-58 + 116 * (i + 1) / n)
    p0 = (R * math.sin(a0), CZ - R * math.cos(a0))
    p1 = (R * math.sin(a1), CZ - R * math.cos(a1))
    lean = 0.35  # the top leans in a little, like a real shell
    t0 = (p0[0] * (1 - lean / R), CZ + (p0[1] - CZ) * (1 - lean / R))
    t1 = (p1[0] * (1 - lean / R), CZ + (p1[1] - CZ) * (1 - lean / R))
    B.quad('shell', (p0[0], 0, p0[1]), (p1[0], 0, p1[1]), (t1[0], H, t1[1]), (t0[0], H, t0[1]))
    # a dark gap between panels
    g = (p1[0], 0, p1[1])
    B.box('wainscot', (g[0] - 0.03, 0, g[2] - 0.03), (g[0] + 0.03, H, g[2] + 0.03))

# side walls, x = +-10.5, z -7.5 .. 18
for sx in (-1, 1):
    x = 10.5 * sx
    zs = (-7.5, 18)
    # wainscot 0 .. 1.2, fabric 1.2 .. 11.5 (faces into the room)
    def wall(key, y0, y1, tile):
        L = zs[1] - zs[0]
        if sx < 0:
            B.quad(key, (x, y0, zs[1]), (x, y0, zs[0]), (x, y1, zs[0]), (x, y1, zs[1]), [(0, 0), (L / tile, 0), (L / tile, (y1 - y0) / tile), (0, (y1 - y0) / tile)])
        else:
            B.quad(key, (x, y0, zs[0]), (x, y0, zs[1]), (x, y1, zs[1]), (x, y1, zs[0]), [(0, 0), (L / tile, 0), (L / tile, (y1 - y0) / tile), (0, (y1 - y0) / tile)])
    wall('wainscot', 0, 1.2, 1.5)
    wall('fabric', 1.2, 11.5, 4.0)
    for k in range(9):
        z = -6 + k * 3.0
        B.box('plaster', (x - 0.25 * sx - 0.2, 0, z - 0.25), (x - 0.25 * sx + 0.2, 11.5, z + 0.25), tile=1.0)
        if k % 2 == 0:
            B.box('sconce', (x - 0.35 * sx - 0.08, 2.9, z + 0.8), (x - 0.35 * sx + 0.08, 3.25, z + 1.0))
    # balcony at 4.5 m over the audience half
    inner = x - 2.0 * sx
    B.box('plaster', (min(x, inner), 4.3, 3.0), (max(x, inner), 5.4, 18), tile=1.0)
    B.box('brass', (min(inner, inner + 0.05 * sx), 5.35, 3.0), (max(inner, inner + 0.05 * sx) + 0.0, 5.42, 18))

# back wall z = 18 and its balcony
B.quad('fabric', (10.5, 1.2, 18), (-10.5, 1.2, 18), (-10.5, 11.5, 18), (10.5, 11.5, 18), [(0, 0), (5, 0), (5, 2.5), (0, 2.5)])
B.quad('wainscot', (10.5, 0, 18), (-10.5, 0, 18), (-10.5, 1.2, 18), (10.5, 1.2, 18), [(0, 0), (14, 0), (14, 1), (0, 1)])
B.box('plaster', (-10.5, 4.3, 15.5), (10.5, 5.4, 18))
# wall behind the shell
B.quad('fabric', (-10.5, 0, -7.5), (10.5, 0, -7.5), (10.5, 11.5, -7.5), (-10.5, 11.5, -7.5), [(0, 0), (5, 0), (5, 2.8), (0, 2.8)])

# ceiling (faces down) and recessed lamps
B.quad('ceiling', (-10.5, 11.5, 18), (10.5, 11.5, 18), (10.5, 11.5, -7.5), (-10.5, 11.5, -7.5), [(0, 0), (3.5, 0), (3.5, 4.2), (0, 4.2)])
for zi in range(-6, 18, 3):
    for xi in (-7.5, -4.5, -1.5, 1.5, 4.5, 7.5):
        B.box('lamp', (xi - 0.18, 11.42, zi - 0.18), (xi + 0.18, 11.5, zi + 0.18))
# a lighting bar over the stage
B.box('brass', (-6, 8.2, -2.0), (6, 8.3, -1.9))
for xi in range(-5, 6, 2):
    B.box('lamp', (xi - 0.12, 8.0, -2.05), (xi + 0.12, 8.2, -1.85))

# audience seats: 12 rows from z 5, 0.95 m apart, 0.56 m wide, aisle |x| < 0.9
for r in range(12):
    z = 5.0 + r * 0.95
    rise = 0.0
    x = -9.6
    while x < 9.6:
        if abs(x + 0.28) > 0.9:
            B.box('velvet', (x + 0.03, 0.42 + rise, z), (x + 0.53, 0.5 + rise, z + 0.5))           # seat
            B.box('velvet', (x + 0.03, 0.5 + rise, z + 0.42), (x + 0.53, 1.0 + rise, z + 0.52))    # back
            B.box('wainscot', (x, 0, z + 0.05), (x + 0.03, 0.62 + rise, z + 0.5))                # arm
        x += 0.56

B.finish()
print('HALL objects', len([o for o in scn.objects]))
bpy.ops.export_scene.gltf(filepath=OUT, export_format='GLB', export_image_format='JPEG', export_jpeg_quality=85, export_yup=True, export_apply=True, export_animations=False, export_cameras=False, export_lights=False)
print('HALL glb', OUT, os.path.getsize(OUT))

if PREVIEW:
    # preview: the orchestra models at their seats + warm stage spots, seen from the audience side
    import re
    src = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'java', 'io', 'embodit', 'insideorchestra', 'domain', 'model', 'Orchestra.kt')
    seats = re.findall(r'Seat\("(\w+)", "[^"]*", "[^"]*", Part\.\w+, Stem\.\w+, (-?[0-9.]+)f, (-?[0-9.]+)f, "(\w+)"', open(src, encoding='utf-8').read())
    mdir = os.path.join(os.path.dirname(OUT))
    for sid, x, z, model in seats:
        x, z = float(x), float(z)
        before = set(scn.objects)
        bpy.ops.import_scene.gltf(filepath=os.path.join(mdir, model + '.glb'))
        for o in [o for o in scn.objects if o not in before and o.parent is None]:
            o.rotation_euler[2] += math.atan2(-x, -z)
            o.location = (x, -z, 0)
    for (lx, lz) in ((-3, -1.5), (0, -2.5), (3, -1.5)):
        sp = bpy.data.objects.new('spot', bpy.data.lights.new('spot', 'SPOT'))
        sp.data.energy = 2600
        sp.data.color = (1.0, 0.86, 0.68)
        sp.data.spot_size = math.radians(70)
        sp.location = G(lx, 8.0, lz + 2.5)
        sp.rotation_euler = (math.radians(20), 0, 0)
        scn.collection.objects.link(sp)
    fill = bpy.data.objects.new('fill', bpy.data.lights.new('fill', 'AREA'))
    fill.data.energy = 900
    fill.data.size = 12
    fill.data.color = (1.0, 0.8, 0.6)
    fill.location = G(0, 10.5, 8)
    scn.collection.objects.link(fill)
    cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam'))
    scn.collection.objects.link(cam)
    cam.location = G(0.3, 1.6, 3.2)
    cam.rotation_euler = (math.radians(84), 0, 0)
    cam.data.lens = 14
    scn.camera = cam
    scn.world = bpy.data.worlds.new('w')
    scn.world.color = (0.02, 0.015, 0.012)
    scn.render.engine = 'BLENDER_EEVEE'
    scn.render.resolution_x, scn.render.resolution_y = 1600, 900
    scn.render.filepath = PREVIEW
    bpy.ops.render.render(write_still=True)
    print('HALL preview', PREVIEW)
