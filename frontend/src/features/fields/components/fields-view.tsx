import { useEffect, useState, type FormEvent } from 'react';
import {
  ArrowRight,
  CalendarDays,
  Camera,
  MessageSquare,
  Pencil,
  Plus,
  Search,
  Sprout,
  Trash2,
  X,
} from 'lucide-react';
import type { FieldProfile } from '@/features/fields/types';
import type { AttachedImage } from '@/features/images/types';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/ui/dialog';
import { NxSelect } from '@/shared/ui/nx-select';
import { EmptyState } from '@/shared/ui/empty-state';
import { ConfirmDelete } from '@/shared/ui/confirm-delete';
import { api, errorText as errorMessage } from '@/shared/api/client';
const newId = (prefix: string) => `${prefix}-${crypto.randomUUID()}`;
const formatDate = (date: string) => (date ? date.replaceAll('-', '.') : '日期待定');
export type FieldsViewProps = {
  fields: FieldProfile[];
  onSave: (value: FieldProfile) => Promise<unknown>;
  onDelete: (value: string) => Promise<unknown>;
  onRecord: (id: string, note: string) => Promise<unknown>;
  onAsk: (fieldId: string) => void;
  /** 「让农心看最近状况」：带着该田块最近的田间照片开一段对话 */
  onAnalyze?: (fieldId: string) => void;
};

const CROPS = ['水稻', '小麦', '玉米', '油菜', '蔬菜', '果树', '其他'];
type FieldDraft = {
  name: string;
  crop: string;
  variety: string;
  sowDate: string;
  areaMu: string;
  notes: string;
};
const fieldDraft = (field?: FieldProfile): FieldDraft => ({
  name: field?.name ?? '',
  crop: field?.crop ?? '',
  variety: field?.variety ?? '',
  sowDate: field?.sowDate ?? '',
  areaMu: field?.areaMu == null ? '' : String(field.areaMu),
  notes: field?.notes ?? '',
});

export function FieldsView({
  fields,
  onSave,
  onDelete,
  onRecord,
  onAsk,
  onAnalyze,
}: FieldsViewProps) {
  const [query, setQuery] = useState('');
  const [editor, setEditor] = useState<{ existing?: FieldProfile; draft: FieldDraft } | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<FieldProfile | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [recordNote, setRecordNote] = useState('');
  const [recordBusy, setRecordBusy] = useState(false);
  const [recordError, setRecordError] = useState('');
  // 田间照片档案：按时间倒序的影像时间轴
  const [photos, setPhotos] = useState<AttachedImage[]>([]);
  const [photoUsage, setPhotoUsage] = useState({ photos: 0, bytes: 0 });
  const [photoError, setPhotoError] = useState('');
  const [preview, setPreview] = useState<AttachedImage | null>(null);
  const [photosLoading, setPhotosLoading] = useState(false);
  useEffect(() => {
    if (!selectedId) {
      setPhotos([]);
      setPhotoUsage({ photos: 0, bytes: 0 });
      return;
    }
    const controller = new AbortController();
    setPhotosLoading(true);
    setPhotoError('');
    void api<{ photos: AttachedImage[]; usage: { photos: number; bytes: number } }>(
      `/uploads/field/${selectedId}`,
      { signal: controller.signal },
    )
      .then((result) => {
        setPhotos(result.photos ?? []);
        setPhotoUsage(result.usage ?? { photos: 0, bytes: 0 });
      })
      .catch((cause) => {
        if (!controller.signal.aborted) setPhotoError(errorMessage(cause));
      })
      .finally(() => {
        if (!controller.signal.aborted) setPhotosLoading(false);
      });
    return () => controller.abort();
  }, [selectedId]);
  async function removePhoto(photo: AttachedImage) {
    setPhotoError('');
    try {
      await api(`/uploads/${photo.id}`, { method: 'DELETE' });
      setPhotos((current) => current.filter((item) => item.id !== photo.id));
      setPhotoUsage((current) => ({
        photos: Math.max(0, current.photos - 1),
        bytes: Math.max(0, current.bytes - photo.bytes),
      }));
      setPreview(null);
    } catch (cause) {
      setPhotoError(errorMessage(cause));
    }
  }
  const selected = fields.find((field) => field.id === selectedId);
  const visible = fields.filter((field) =>
    `${field.name} ${field.crop} ${field.variety ?? ''}`
      .toLowerCase()
      .includes(query.trim().toLowerCase()),
  );
  const area = fields.reduce((total, field) => total + (field.areaMu ?? 0), 0);
  const measuredFields = fields.filter((field) => field.areaMu != null).length;

  const edit = (field?: FieldProfile) => {
    setError('');
    setEditor({ existing: field, draft: fieldDraft(field) });
  };
  const updateDraft = (key: keyof FieldDraft, value: string) =>
    setEditor((current) => current && { ...current, draft: { ...current.draft, [key]: value } });
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!editor || busy) return;
    const draft = editor.draft;
    if (!draft.name.trim() || !draft.crop.trim() || !draft.sowDate) {
      setError('请填写田块名称、作物和播种日期。');
      return;
    }
    const areaMu = draft.areaMu === '' ? undefined : Number(draft.areaMu);
    if (areaMu !== undefined && (!Number.isFinite(areaMu) || areaMu <= 0)) {
      setError('面积需要填写大于 0 的数字。');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await onSave({
        ...editor.existing,
        id: editor.existing?.id ?? newId('f'),
        name: draft.name.trim(),
        crop: draft.crop,
        variety: draft.variety.trim() || undefined,
        sowDate: draft.sowDate,
        areaMu,
        notes: draft.notes.trim() || undefined,
        records: editor.existing?.records ?? [],
      });
      setEditor(null);
      setStatus(editor.existing ? '田块信息已更新。' : '田块已建立。');
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  };
  const saveRecord = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selected || !recordNote.trim() || recordBusy) return;
    setRecordBusy(true);
    setRecordError('');
    try {
      await onRecord(selected.id, recordNote.trim());
      setRecordNote('');
      setStatus('种植记录已保存。');
    } catch (cause) {
      setRecordError(errorMessage(cause));
    } finally {
      setRecordBusy(false);
    }
  };

  return (
    <section className="nx-view nx-fields-view" aria-labelledby="nx-fields-title">
      <header className="nx-view-head">
        <div>
          <p className="nx-eyebrow">FIELD NOTEBOOK / 田间档案</p>
          <h1 id="nx-fields-title">每一块田，都有自己的故事。</h1>
          <p className="nx-muted">把作物、播期和日常观察记在一起，农事讨论更有依据。</p>
        </div>
        <button className="nx-button is-primary" onClick={() => edit()}>
          <Plus size={18} />
          新建田块
        </button>
      </header>
      <div className="nx-view-summary">
        <span>
          <strong>{fields.length}</strong> 块田块
        </span>
        <span>
          <strong>
            {measuredFields ? area.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : '—'}
          </strong>{' '}
          亩已登记面积
        </span>
        <span className="nx-muted">手动记录 · 尚未接入田间监测</span>
      </div>
      <p className="nx-status" role="status">
        {status}
      </p>
      {fields.length === 0 ? (
        <EmptyState
          icon={<Sprout size={30} />}
          title="从第一块田开始"
          action={
            <button className="nx-button is-primary" onClick={() => edit()}>
              <Plus size={17} />
              建立田块档案
            </button>
          }
        >
          记录种什么、何时播种，以及这块田值得记住的事。
        </EmptyState>
      ) : (
        <>
          <div className="nx-toolbar">
            <label className="nx-search">
              <Search size={18} aria-hidden="true" />
              <input
                aria-label="搜索田块"
                placeholder="搜索田块、作物或品种"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </label>
            <span className="nx-muted">{visible.length} 块田块</span>
          </div>
          {visible.length === 0 ? (
            <EmptyState
              icon={<Search size={26} />}
              title="没有找到这块田"
              action={
                <button className="nx-button" onClick={() => setQuery('')}>
                  清除搜索
                </button>
              }
            >
              试试田块名称或作物名称。
            </EmptyState>
          ) : (
            <div className="nx-grid nx-field-grid">
              {visible.map((field) => (
                <article className="nx-card nx-field-card" key={field.id}>
                  <div
                    className={`nx-field-cover nx-crop-${CROPS.indexOf(field.crop) % 3}`}
                    aria-hidden="true"
                  >
                    <span className="nx-field-contour" />
                    <Sprout size={36} />
                    <span>{field.crop}</span>
                  </div>
                  <div className="nx-card-body">
                    <div className="nx-card-topline">
                      <span className="nx-badge">{field.crop}</span>
                      <div className="nx-inline-actions">
                        <button
                          className="nx-icon-button"
                          aria-label={`编辑${field.name}`}
                          onClick={() => edit(field)}
                        >
                          <Pencil size={15} />
                        </button>
                        <button
                          className="nx-icon-button"
                          aria-label={`删除${field.name}`}
                          onClick={() => setDeleteTarget(field)}
                        >
                          <Trash2 size={15} />
                        </button>
                      </div>
                    </div>
                    <button
                      className="nx-card-title-button"
                      onClick={() => {
                        setSelectedId(field.id);
                        setRecordNote('');
                        setRecordError('');
                      }}
                    >
                      <h2>{field.name}</h2>
                      <ArrowRight size={18} />
                    </button>
                    <p className="nx-muted">
                      {field.variety || '品种未填写'}
                      <span aria-hidden="true"> · </span>
                      {field.areaMu == null ? '面积未填写' : `${field.areaMu} 亩`}
                    </p>
                    <div className="nx-field-meta">
                      <CalendarDays size={15} />
                      <span>{formatDate(field.sowDate)} 播种</span>
                    </div>
                    <div className="nx-card-footer">
                      <span>{field.records?.length ?? 0} 条种植记录</span>
                      <button className="nx-text-button" onClick={() => onAsk(field.id)}>
                        聊聊这块田 <ArrowRight size={14} />
                      </button>
                    </div>
                  </div>
                </article>
              ))}
            </div>
          )}
        </>
      )}

      <Dialog
        open={editor !== null}
        onOpenChange={(open) => {
          if (!open && !busy) setEditor(null);
        }}
      >
        <DialogContent className="nx-dialog" showCloseButton={!busy}>
          <DialogHeader>
            <DialogTitle>{editor?.existing ? '编辑田块' : '建立一块田的档案'}</DialogTitle>
            <DialogDescription>带 * 的项目为必填，其余信息可以之后补充。</DialogDescription>
          </DialogHeader>
          {editor && (
            <form className="nx-form" onSubmit={(event) => void save(event)}>
              <label className="nx-form-field">
                田块名称 *
                <input
                  autoFocus
                  required
                  maxLength={120}
                  placeholder="例如：河边一号田"
                  value={editor.draft.name}
                  onChange={(event) => updateDraft('name', event.target.value)}
                />
              </label>
              <div className="nx-form-grid">
                <label className="nx-form-field">
                  作物 *
                  <NxSelect
                    ariaLabel="作物"
                    value={editor.draft.crop}
                    placeholder="请选择作物"
                    options={Array.from(new Set([...CROPS, editor.draft.crop]))
                      .filter(Boolean)
                      .map((crop) => ({ value: crop, label: crop }))}
                    onValueChange={(value) => updateDraft('crop', value)}
                  />
                </label>
                <label className="nx-form-field">
                  品种
                  <input
                    maxLength={120}
                    placeholder="选填"
                    value={editor.draft.variety}
                    onChange={(event) => updateDraft('variety', event.target.value)}
                  />
                </label>
              </div>
              <div className="nx-form-grid">
                <label className="nx-form-field">
                  播种日期 *
                  <input
                    type="date"
                    required
                    min="1900-01-01"
                    max="2100-12-31"
                    value={editor.draft.sowDate}
                    onChange={(event) => updateDraft('sowDate', event.target.value)}
                  />
                </label>
                <label className="nx-form-field">
                  面积（亩）
                  <input
                    type="number"
                    min="0.001"
                    step="any"
                    placeholder="选填"
                    value={editor.draft.areaMu}
                    onChange={(event) => updateDraft('areaMu', event.target.value)}
                  />
                </label>
              </div>
              <label className="nx-form-field">
                田块备注
                <textarea
                  rows={3}
                  maxLength={8000}
                  placeholder="土质、灌溉方式，或其他值得留意的情况"
                  value={editor.draft.notes}
                  onChange={(event) => updateDraft('notes', event.target.value)}
                />
              </label>
              {error && (
                <p className="nx-error" role="alert">
                  {error}
                </p>
              )}
              <div className="nx-dialog-actions">
                <button
                  type="button"
                  className="nx-button"
                  disabled={busy}
                  onClick={() => setEditor(null)}
                >
                  取消
                </button>
                <button type="submit" className="nx-button is-primary" disabled={busy}>
                  {busy ? '正在保存…' : '保存田块'}
                </button>
              </div>
            </form>
          )}
        </DialogContent>
      </Dialog>

      <Dialog
        open={!!selected}
        onOpenChange={(open) => {
          if (!open && !recordBusy) setSelectedId(null);
        }}
      >
        <DialogContent className="nx-dialog nx-dialog-wide" showCloseButton={!recordBusy}>
          {selected && (
            <>
              <DialogHeader>
                <DialogTitle>{selected.name}</DialogTitle>
                <DialogDescription>
                  {selected.crop} · {selected.variety || '品种未填'} ·{' '}
                  {formatDate(selected.sowDate)} 播种
                </DialogDescription>
              </DialogHeader>
              <div className="nx-field-detail">
                <div className="nx-detail-facts">
                  <span>
                    面积{' '}
                    <strong>{selected.areaMu == null ? '未填写' : `${selected.areaMu} 亩`}</strong>
                  </span>
                  <span>
                    数据来源 <strong>手动记录</strong>
                  </span>
                </div>
                {selected.notes && <p className="nx-note">{selected.notes}</p>}
                <div className="nx-field-photos">
                  <div className="nx-section-heading">
                    <h3>田间照片</h3>
                    <span className="nx-muted">
                      {[
                        photoUsage.photos || photos.length,
                        photoUsage.bytes ? `${(photoUsage.bytes / 1024 / 1024).toFixed(1)} MB` : '',
                      ]
                        .filter(Boolean)
                        .join(' 张 · ')}
                      {photoUsage.bytes ? '' : ' 张'}
                    </span>
                  </div>
                  <header>
                    <b>
                      {photosLoading
                        ? '正在读取…'
                        : photos.length
                          ? `最近一次 ${formatDate((photos[0].observedAt || photos[0].createdAt || '').slice(0, 10))}`
                          : '还没有照片'}
                    </b>
                    <div className="nx-inline-actions">
                      {onAnalyze && (
                        <button
                          className="nx-text-button"
                          onClick={() => {
                            setSelectedId(null);
                            onAnalyze(selected.id);
                          }}
                        >
                          <Camera size={14} />
                          让农心看最近状况 <ArrowRight size={13} />
                        </button>
                      )}
                    </div>
                  </header>
                  {photoError && (
                    <p className="nx-error" role="alert">
                      {photoError}
                    </p>
                  )}
                  {photos.length > 0 && (
                    <ul className="nx-photo-grid">
                      {photos.map((photo) => (
                        <li key={photo.id}>
                          <img
                            src={photo.url}
                            alt={`${photo.observedAt || ''} 拍摄的田间照片`}
                            onClick={() => setPreview(photo)}
                          />
                          <figcaption>
                            <b>
                              {formatDate((photo.observedAt || photo.createdAt || '').slice(0, 10))}
                            </b>
                            {photo.note ? (
                              <span className="nx-photo-note">{photo.note}</span>
                            ) : (
                              <span className="nx-photo-note">暂无备注</span>
                            )}
                          </figcaption>
                          <button
                            type="button"
                            aria-label={`删除 ${photo.observedAt || ''} 的照片`}
                            onClick={() => void removePhoto(photo)}
                          >
                            <X size={12} />
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                  {!photosLoading && photos.length === 0 && (
                    <p className="nx-inline-empty">
                      在对话里给这块田拍照提问，照片就会按日期存到这里；以后可以对比不同时间的叶色与病斑变化。
                    </p>
                  )}
                </div>
                <div className="nx-section-heading">
                  <h3>种植记录</h3>
                  <span className="nx-muted">{selected.records?.length ?? 0} 条</span>
                </div>
                {selected.records?.length ? (
                  <ol className="nx-record-list">
                    {[...selected.records].reverse().map((record, index) => (
                      <li key={`${record.date}-${index}`}>
                        <time>{formatDate(record.date)}</time>
                        <p>{record.note}</p>
                      </li>
                    ))}
                  </ol>
                ) : (
                  <p className="nx-inline-empty">
                    还没有记录。把今天看到的长势、做过的农活记下来。
                  </p>
                )}
                <form className="nx-form" onSubmit={(event) => void saveRecord(event)}>
                  <label className="nx-form-field">
                    添加今天的观察
                    <textarea
                      required
                      rows={3}
                      maxLength={8000}
                      placeholder="例如：今天查看了排水沟，南侧田角有少量积水。"
                      value={recordNote}
                      onChange={(event) => setRecordNote(event.target.value)}
                    />
                  </label>
                  {recordError && (
                    <p className="nx-error" role="alert">
                      {recordError}
                    </p>
                  )}
                  <button
                    className="nx-button is-primary"
                    disabled={recordBusy || !recordNote.trim()}
                    type="submit"
                  >
                    <Plus size={16} />
                    {recordBusy ? '正在保存…' : '保存记录'}
                  </button>
                </form>
              </div>
              <div className="nx-dialog-actions">
                <button
                  className="nx-button"
                  disabled={recordBusy}
                  onClick={() => {
                    setSelectedId(null);
                    edit(selected);
                  }}
                >
                  <Pencil size={16} />
                  编辑田块
                </button>
                <button
                  className="nx-button is-primary"
                  disabled={recordBusy}
                  onClick={() => {
                    setSelectedId(null);
                    onAsk(selected.id);
                  }}
                >
                  <MessageSquare size={16} />
                  聊聊这块田
                </button>
              </div>
            </>
          )}
        </DialogContent>
      </Dialog>
      <Dialog
        open={!!preview}
        onOpenChange={(open) => {
          if (!open) setPreview(null);
        }}
      >
        <DialogContent className="nx-dialog nx-dialog-wide">
          {preview && (
            <>
              <DialogHeader>
                <DialogTitle>
                  {formatDate((preview.observedAt || preview.createdAt || '').slice(0, 10))}{' '}
                  的田间照片
                </DialogTitle>
                <DialogDescription>
                  {preview.width}×{preview.height} · {Math.round(preview.bytes / 1024)} KB
                  {preview.note ? ` · ${preview.note}` : ''}
                </DialogDescription>
              </DialogHeader>
              <img className="nx-photo-large" src={preview.url} alt="田间照片大图" />
              <div className="nx-dialog-actions">
                <button className="nx-button" onClick={() => setPreview(null)}>
                  关闭
                </button>
                <button className="nx-button is-danger" onClick={() => void removePhoto(preview)}>
                  <Trash2 size={15} />
                  删除这张
                </button>
              </div>
            </>
          )}
        </DialogContent>
      </Dialog>
      <ConfirmDelete
        open={!!deleteTarget}
        title={`删除“${deleteTarget?.name ?? ''}”？`}
        description="该田块的档案与种植记录将被删除，删除后无法恢复。"
        onClose={() => setDeleteTarget(null)}
        onConfirm={async () => {
          if (deleteTarget) {
            await onDelete(deleteTarget.id);
            if (selectedId === deleteTarget.id) setSelectedId(null);
            setStatus('田块已删除。');
          }
        }}
      />
    </section>
  );
}
