import { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { STLLoader } from 'three/addons/loaders/STLLoader.js';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { Minus, Plus, RotateCcw } from 'lucide-react';
import { translateUi, useLanguage } from './i18n';

function disposeModel(object: THREE.Object3D) {
  const textures = new Set<THREE.Texture>();
  object.traverse(child => {
    if (!(child instanceof THREE.Mesh)) return;
    child.geometry.dispose();
    for (const material of Array.isArray(child.material) ? child.material : [child.material]) {
      for (const value of Object.values(material)) if (value instanceof THREE.Texture) textures.add(value);
      material.dispose();
    }
  });
  textures.forEach(texture => { const image = texture.source.data; if (typeof ImageBitmap !== 'undefined' && image instanceof ImageBitmap) image.close(); texture.dispose(); });
}

export default function CadViewer({ blob, filename, interactive = false }: { blob: Blob; filename: string; interactive?: boolean }) {
  useLanguage();
  const host = useRef<HTMLDivElement>(null);
  const actions = useRef<{ zoom: (factor: number) => void; reset: () => void } | null>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading'), [snapshot, setSnapshot] = useState('');
  useEffect(() => {
    const element = host.current!;
    let cancelled = false, renderer: THREE.WebGLRenderer | undefined, controls: OrbitControls | undefined, object: THREE.Object3D | undefined, resize: ResizeObserver | undefined;
    setStatus('loading'); setSnapshot('');
    const clean = () => { actions.current = null; resize?.disconnect(); controls?.dispose(); if (object) { disposeModel(object); object = undefined; } if (renderer) { renderer.dispose(); renderer.forceContextLoss(); renderer.domElement.remove(); renderer = undefined; } };
    void (async () => {
      try {
        const data = await blob.arrayBuffer();
        if (cancelled) return;
        if (/\.stl$/i.test(filename)) {
          const geometry = new STLLoader().parse(data);
          geometry.computeVertexNormals();
          object = new THREE.Mesh(geometry, new THREE.MeshStandardMaterial({ color: 0x5b90b4, metalness: .2, roughness: .48, side: THREE.DoubleSide }));
        } else {
          const manager = new THREE.LoadingManager();
          // GLB previews must be self contained. Never load file-authored external URLs.
          manager.setURLModifier(url => { if (!/^(data:|blob:)/.test(url)) throw new Error('External GLB resource'); return url; });
          object = (await new GLTFLoader(manager).parseAsync(data, '')).scene;
        }
        if (cancelled) { clean(); return; }
        const bounds = new THREE.Box3().setFromObject(object), size = bounds.getSize(new THREE.Vector3());
        const maximum = Math.max(size.x, size.y, size.z);
        if (bounds.isEmpty() || !Number.isFinite(maximum) || maximum <= 0) throw new Error('Empty model');
        const center = bounds.getCenter(new THREE.Vector3());
        object.position.sub(center); const normalized = new THREE.Group(); normalized.add(object); normalized.scale.setScalar(2.8 / maximum);
        const scene = new THREE.Scene(); scene.add(normalized);
        scene.add(new THREE.HemisphereLight(0xffffff, 0x778899, 2.8));
        const light = new THREE.DirectionalLight(0xffffff, 3.5); light.position.set(4, 6, 5); scene.add(light);
        const back = new THREE.DirectionalLight(0xc6ddff, 2); back.position.set(-4, 1, -3); scene.add(back);
        const camera = new THREE.PerspectiveCamera(38, 1, .01, 100);
        renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: !interactive });
        renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5)); renderer.setClearColor(0x000000, 0);
        element.append(renderer.domElement);
        const render = () => { if (renderer) renderer.render(scene, camera); };
        const fit = () => { const width = Math.max(100, element.clientWidth), height = Math.max(100, element.clientHeight); camera.aspect = width / height; camera.updateProjectionMatrix(); renderer?.setSize(width, height, false); render(); };
        const reset = () => { camera.position.set(4.4, 3.2, 4.4); camera.lookAt(0, 0, 0); if (controls) { controls.target.set(0, 0, 0); controls.update(); } render(); };
        if (interactive) {
          controls = new OrbitControls(camera, renderer.domElement); controls.enableDamping = false; controls.minDistance = 1.7; controls.maxDistance = 18;
          controls.addEventListener('change', render); controls.listenToKeyEvents(element);
          actions.current = { reset, zoom: factor => { camera.position.sub(controls!.target).multiplyScalar(factor).add(controls!.target); controls!.update(); render(); } };
        }
        reset(); fit();
        if (interactive) { resize = new ResizeObserver(fit); resize.observe(element); }
        else { setSnapshot(renderer.domElement.toDataURL('image/png')); clean(); }
        setStatus('ready');
      } catch { clean(); if (!cancelled) setStatus('error'); }
    })();
    return () => { cancelled = true; clean(); };
  }, [blob, filename, interactive]);
  return <div className={interactive ? 'cad-viewer' : 'cad-thumbnail'}>
    <div ref={host} className="cad-stage" tabIndex={interactive ? 0 : undefined} role={interactive ? 'region' : undefined} aria-label={translateUi('تصميم ثلاثي الأبعاد: {{v0}}', { v0: filename })}>
      {snapshot && <img src={snapshot} alt={filename} />}
    </div>
    {status === 'loading' && <span className="cad-status" role="status">{translateUi('جارٍ تحميل التصميم')}</span>}
    {status === 'error' && <p className="cad-status" role="alert">{translateUi('تعذر عرض التصميم. ارفع STL أو GLB صالحًا وغير مضغوط، أو صورة معاينة.')}</p>}
    {interactive && <><div className="cad-controls"><button type="button" disabled={status !== 'ready'} aria-label={translateUi('تقريب التصميم')} onClick={() => actions.current?.zoom(.8)}><Plus size={18} /></button><button type="button" disabled={status !== 'ready'} aria-label={translateUi('إبعاد التصميم')} onClick={() => actions.current?.zoom(1.25)}><Minus size={18} /></button><button type="button" disabled={status !== 'ready'} onClick={() => actions.current?.reset()}><RotateCcw size={16} />{translateUi('إعادة ضبط العرض')}</button></div><p className="muted">{translateUi('اسحب للتدوير، واستخدم عجلة الماوس للتقريب. الأسهم تحرك التصميم عند تحديد العارض.')}</p></>}
  </div>;
}
