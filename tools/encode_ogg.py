"""WAV -> OGG Vorbis using Blender's audio mixdown (no other encoder on the build machine).
Run: blender --background --python tools/encode_ogg.py -- <wav dir> <out dir>"""
import bpy, sys, os
a = sys.argv[sys.argv.index('--') + 1:]
src, out = a[0], a[1]
os.makedirs(out, exist_ok=True)
for f in sorted(os.listdir(src)):
    if not f.endswith('.wav'):
        continue
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scn = bpy.context.scene
    scn.sequence_editor_create()
    seq = scn.sequence_editor
    coll = seq.strips if hasattr(seq, 'strips') else seq.sequences
    snd = coll.new_sound(f[:-4], os.path.join(src, f), 1, 1)
    import wave
    with wave.open(os.path.join(src, f)) as w:
        secs = w.getnframes() / w.getframerate()
    scn.render.fps = 100
    scn.render.fps_base = 1.0
    scn.frame_start = 1
    scn.frame_end = int(secs * 100) + 2
    scn.render.ffmpeg.audio_mixrate = 32000
    p = os.path.join(out, f[:-4] + '.ogg')
    bpy.ops.sound.mixdown(filepath=p, check_existing=False, container='OGG', codec='VORBIS',
                          format='S16', mixrate=32000, bitrate=96, accuracy=1024, split_channels=False)
    import time
    # the mixdown finishes on a worker thread: wait until the file stops growing
    last, still = -1, 0
    for _ in range(1200):
        n = os.path.getsize(p) if os.path.exists(p) else -1
        still = still + 1 if n == last and n > 0 else 0
        if still >= 30:
            break
        last = n
        time.sleep(0.1)
    print('OGG', p, os.path.getsize(p) if os.path.exists(p) else -1, 'frames', scn.frame_end)
