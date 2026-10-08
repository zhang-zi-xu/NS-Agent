import { useState } from 'react';
import { Sprout } from 'lucide-react';

export function BrandMark({ small = false }: { small?: boolean }) {
  const [imageReady, setImageReady] = useState(false);
  return (
    <span className={`nx-brand-mark${small ? ' is-small' : ''}`}>
      <img
        src="/brand/nongxin-mark.png"
        alt=""
        onLoad={() => setImageReady(true)}
        onError={() => setImageReady(false)}
        style={{ display: imageReady ? 'block' : 'none' }}
      />
      {!imageReady && <Sprout size={small ? 19 : 26} />}
    </span>
  );
}
