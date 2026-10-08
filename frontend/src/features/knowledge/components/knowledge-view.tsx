import { useState } from 'react';
import { ArrowRight, BookOpen, Leaf, MessageSquare, Search } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/ui/dialog';
import { NxSelect } from '@/shared/ui/nx-select';
import { EmptyState } from '@/shared/ui/empty-state';
import type { KnowledgeSource } from '@/features/knowledge/types';
export type KnowledgeViewProps = {
  entries?: KnowledgeSource[];
  onAsk: (question: string) => void;
};

export function KnowledgeView({ entries = [], onAsk }: KnowledgeViewProps) {
  const [query, setQuery] = useState('');
  const [crop, setCrop] = useState('全部');
  const [status, setStatus] = useState('全部');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = entries.find((entry) => entry.id === selectedId);
  const crops = ['全部', ...new Set(entries.flatMap((entry) => entry.crops ?? []))];
  const verifiedCount = entries.filter((entry) => entry.reviewStatus === 'verified').length;
  const visible = entries.filter((entry) => {
    if (crop !== '全部' && !(entry.crops ?? []).includes(crop)) return false;
    if (status === '已核验' && entry.reviewStatus !== 'verified') return false;
    if (status === '未核验' && entry.reviewStatus !== 'unverified') return false;
    const haystack = [
      entry.title,
      entry.institution,
      entry.region,
      (entry.crops ?? []).join(' '),
      entry.topic,
      ...(entry.chunks ?? []).map((chunk) => `${chunk.heading ?? ''} ${chunk.text ?? ''}`),
    ].join(' ');
    return haystack.toLowerCase().includes(query.trim().toLowerCase());
  });

  return (
    <section className="nx-view nx-knowledge-view" aria-labelledby="nx-knowledge-title">
      <header className="nx-view-head">
        <div>
          <p className="nx-eyebrow">SOURCED LIBRARY / 农技资料</p>
          <h1 id="nx-knowledge-title">好判断，从了解开始。</h1>
          <p className="nx-muted">
            每条资料都登记了机构、日期、适用地区与原文链接，对话中的引用只从这里取。
          </p>
        </div>
        <span className="nx-badge is-verified">
          <BookOpen size={15} />
          已核验原文 {verifiedCount} 篇
        </span>
      </header>
      <div className="nx-source-note">
        <BookOpen size={18} aria-hidden="true" />
        <p>
          「已核验原文」表示已逐条对照公开原文登记，可点开链接核对；「本地草稿 ·
          未核验」是整理材料，只能作为线索。涉及用药剂量与登记信息，一律以当地有效登记标签为准。
        </p>
      </div>
      <div className="nx-toolbar">
        <label className="nx-search">
          <Search size={18} aria-hidden="true" />
          <input
            aria-label="搜索农技资料"
            placeholder="搜索作物、病虫害或问题，如：稻瘟病、赤霉病、高温"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <NxSelect
          ariaLabel="按核验状态筛选"
          className="nx-filter-trigger"
          value={status}
          options={['全部', '已核验', '未核验'].map((item) => ({
            value: item,
            label: item === '全部' ? '全部状态' : item,
          }))}
          onValueChange={setStatus}
        />
      </div>
      <div className="nx-knowledge-filter">
        <div className="nx-filter-tabs" aria-label="按作物筛选资料">
          {crops.map((item) => (
            <button
              key={item}
              aria-pressed={crop === item}
              className={crop === item ? 'is-active' : ''}
              onClick={() => setCrop(item)}
            >
              {item}
            </button>
          ))}
        </div>
        <span className="nx-muted">{visible.length} 篇来源登记</span>
      </div>
      {visible.length === 0 ? (
        <EmptyState
          icon={<BookOpen size={28} />}
          title={entries.length ? '还没有匹配的资料' : '资料正在整理'}
          action={
            entries.length ? (
              <button
                className="nx-button"
                onClick={() => {
                  setQuery('');
                  setCrop('全部');
                  setStatus('全部');
                }}
              >
                重置筛选
              </button>
            ) : undefined
          }
        >
          {entries.length ? '试试其他关键词，或切换作物和状态。' : '当前没有可浏览的来源登记。'}
        </EmptyState>
      ) : (
        <div className="nx-grid nx-knowledge-grid">
          {visible.map((entry) => (
            <button
              className="nx-card nx-knowledge-card"
              key={entry.id}
              onClick={() => setSelectedId(entry.id)}
            >
              <div className="nx-card-topline">
                <span className="nx-knowledge-icon" aria-hidden="true">
                  <Leaf size={21} />
                </span>
                <span
                  className={`nx-badge ${entry.reviewStatus === 'verified' ? 'is-verified' : 'is-draft'}`}
                >
                  {entry.reviewStatus === 'verified' ? '已核验原文' : '本地草稿 · 未核验'}
                </span>
              </div>
              <h2>{entry.title}</h2>
              <p className="nx-knowledge-excerpt">
                {[
                  entry.institution,
                  entry.publishedAt,
                  entry.region ? `适用地区：${entry.region}` : null,
                ]
                  .filter(Boolean)
                  .join(' · ')}
              </p>
              <div className="nx-card-footer">
                <span>
                  {(entry.crops ?? []).join('、') || '作物未标注'} · {entry.chunks?.length ?? 0}{' '}
                  条原文片段
                </span>
                <ArrowRight size={17} />
              </div>
            </button>
          ))}
        </div>
      )}
      <Dialog
        open={!!selected}
        onOpenChange={(open) => {
          if (!open) setSelectedId(null);
        }}
      >
        <DialogContent className="nx-dialog nx-dialog-wide nx-knowledge-dialog">
          {selected && (
            <>
              <DialogHeader>
                <span
                  className={`nx-badge ${selected.reviewStatus === 'verified' ? 'is-verified' : 'is-draft'}`}
                >
                  {(selected.crops ?? []).join('、') || '作物未标注'} ·{' '}
                  {selected.reviewStatus === 'verified' ? '已核验原文' : '本地草稿 · 未核验'}
                </span>
                <DialogTitle>{selected.title}</DialogTitle>
                <DialogDescription>{selected.reviewNote || '来源登记信息如下。'}</DialogDescription>
              </DialogHeader>
              <article className="nx-knowledge-detail">
                <section>
                  <h3>来源登记</h3>
                  <dl className="nx-source-fields">
                    <div>
                      <dt>机构</dt>
                      <dd>{selected.institution || '未登记'}</dd>
                    </div>
                    <div>
                      <dt>发布日期</dt>
                      <dd>{selected.publishedAt || '未登记'}</dd>
                    </div>
                    <div>
                      <dt>抓取日期</dt>
                      <dd>{selected.fetchedAt || '未登记'}</dd>
                    </div>
                    <div>
                      <dt>适用地区</dt>
                      <dd>{selected.region || '未标注'}</dd>
                    </div>
                    <div>
                      <dt>适用作物</dt>
                      <dd>{(selected.crops ?? []).join('、') || '未标注'}</dd>
                    </div>
                    <div>
                      <dt>版本</dt>
                      <dd>{selected.version || '未登记'}</dd>
                    </div>
                    <div>
                      <dt>许可说明</dt>
                      <dd>{selected.license || '未登记'}</dd>
                    </div>
                  </dl>
                  {selected.url ? (
                    <a
                      className="nx-source-link"
                      href={selected.url}
                      target="_blank"
                      rel="noreferrer"
                    >
                      查看原文链接
                    </a>
                  ) : (
                    <p className="nx-source-nolink">
                      没有可核验的原文链接：这是本地整理草稿，不能作为官方依据。
                    </p>
                  )}
                </section>
                {(selected.chunks ?? []).map((chunk) => (
                  <section key={chunk.id}>
                    <h3>{chunk.heading || '原文片段'}</h3>
                    <p className="nx-source-locator">
                      定位：{chunk.locator || '未标注'} · 适用：
                      {[chunk.crop, chunk.region, chunk.growthStage].filter(Boolean).join(' / ') ||
                        '未标注'}
                    </p>
                    <p>{chunk.text}</p>
                  </section>
                ))}
              </article>
              <div className="nx-dialog-actions">
                <button className="nx-button" onClick={() => setSelectedId(null)}>
                  关闭资料
                </button>
                <button
                  className="nx-button is-primary"
                  onClick={() => {
                    setSelectedId(null);
                    onAsk(
                      `我看了资料“${selected.title}”（来源ID：${selected.chunks?.[0]?.id ?? selected.id}）。这条是否适用于我的田块？请说明适用条件，以及还需要我现场核实什么。`,
                    );
                  }}
                >
                  <MessageSquare size={16} />
                  带着问题聊一聊
                </button>
              </div>
            </>
          )}
        </DialogContent>
      </Dialog>
    </section>
  );
}
