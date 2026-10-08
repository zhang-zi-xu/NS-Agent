import { useState } from 'react';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from './dialog';
import { errorText as errorMessage } from '@/shared/api/client';

export function ConfirmDelete({
  open,
  title,
  description,
  onClose,
  onConfirm,
}: {
  open: boolean;
  title: string;
  description: string;
  onClose: () => void;
  onConfirm: () => Promise<unknown>;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const remove = async () => {
    setError('');
    setBusy(true);
    try {
      await onConfirm();
      onClose();
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog
      open={open}
      onOpenChange={(value) => {
        if (!value && !busy) {
          setError('');
          onClose();
        }
      }}
    >
      <DialogContent className="nx-dialog nx-dialog-compact" showCloseButton={!busy}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        {error && (
          <p className="nx-error" role="alert">
            {error}
          </p>
        )}
        <div className="nx-dialog-actions">
          <button className="nx-button" disabled={busy} onClick={onClose}>
            取消
          </button>
          <button className="nx-button is-danger" disabled={busy} onClick={() => void remove()}>
            {busy ? '正在删除…' : '确认删除'}
          </button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
