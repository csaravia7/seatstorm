import { useEffect } from 'react';
import { usePurchaseStore } from '../state/purchaseStore';

export function ToastHost() {
  const toast = usePurchaseStore((s) => s.toast);
  const clear = usePurchaseStore((s) => s.clearToast);
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(clear, 4500);
    return () => clearTimeout(t);
  }, [toast, clear]);
  if (!toast) return null;
  return (
    <div className={`toast toast-${toast.level}`} role="status" aria-live="polite" onClick={clear}>
      {toast.text}
    </div>
  );
}
