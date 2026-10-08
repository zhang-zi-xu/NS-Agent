import type { CSSProperties } from 'react';
import {
  ArrowRight,
  ArrowUp,
  CheckCheck,
  Download,
  LoaderCircle,
  ShieldCheck,
  Sprout,
  Square,
} from 'lucide-react';

import { ContextPanel } from '@/features/context/components/context-panel';
import { placeDisplayName } from '@/features/context/display-labels';
import { DataAttach } from '@/features/chat/components/data-attach';
import { ImageAttach, ImageStrip } from '@/features/images/components/image-attach';
import { VoiceInput } from '@/features/chat/components/voice-input';
import { PlanCard, RiskCard } from '@/features/chat/components/plan-card';
import { SourceCard } from '@/features/knowledge/components/source-card';
import { ClarifyCard } from '@/features/chat/components/clarify-card';

import { NxSelect } from '@/shared/ui/nx-select';
import { MiniMd } from '@/shared/ui/mini-md';
import { errorText } from '@/shared/api/client';
import { hasClarifyFollowUp, setClarifyAnswer } from '@/features/chat/clarification';
import { summaryCountText } from '@/features/chat/history-summary';
import { retryMessages } from '@/features/chat/stream';
import { imageFilesFrom } from '@/features/images/uploads';

import { BrandMark } from '@/shared/ui/brand-mark';
import { STARTERS } from '../navigation';
import type { WorkspaceModel } from '../use-workspace';

export function WorkspaceChat({ workspace }: { workspace: WorkspaceModel }) {
  const {
    current,
    setCurrent,
    query,
    setQuery,
    attachment,
    setAttachment,
    images,
    setImages,
    visionReady,
    fieldPhotos,
    autoFieldPhotos,
    setAutoFieldPhotos,
    imageError,
    imageBusy,
    addPhotos,
    handlePaste,
    clarifyAnswers,
    setClarifyAnswers,
    sending,
    queued,
    cancelQueued,
    chatError,
    streamStatus,
    includeWeather,
    setIncludeWeather,
    conversationBusy,
    saveError,
    setSaveError,
    inputRef,
    abortRef,
    scrollRef,
    followScroll,
    activeField,
    latestPlanId,
    previewLine,
    incompleteHint,
    fresh,
    suggest,
    persist,
    send,
    exportConversation,
    fields,
    tasks,
    ready,
    setNotice,
    showArt,
    setShowArt,
    setPlanTarget,
    planBusy,
    setPlanError,
    rail,
    railVisible,
    pendingTasks,
    go,
    context,
    pendingContext,
    now,
    contextBusy,
    contextError,
    locate,
    confirmLocation,
    searchCity,
    clearContext,
  } = workspace;
  return (
    <div
      className={`nx-chat-layout${current.messages.length ? ' has-messages' : ''}`}
      style={{ '--nx-rail-width': `${rail.width}px` } as CSSProperties}
    >
      <div className="nx-chat-main">
        <div className="nx-conversation-bar">
          <div className="nx-field-select">
            <NxSelect
              ariaLabel="本次对话关联田块"
              value={current.fieldId || ''}
              disabled={sending || !!saveError || conversationBusy}
              leading={<Sprout size={15} />}
              options={[
                { value: '', label: '不关联田块', hint: '通用咨询' },
                ...fields.map((f) => ({ value: f.id, label: f.name, hint: f.crop })),
              ]}
              footer={
                <button type="button" onClick={() => go('fields')}>
                  管理田块档案 <ArrowRight size={13} />
                </button>
              }
              emptyText="还没有田块档案"
              onValueChange={(value) => {
                const fieldId = value || null;
                if (current.messages.length) {
                  fresh(fieldId);
                  setNotice('已为所选田块开启新对话，避免混用田块信息。');
                } else setCurrent((prev) => ({ ...prev, fieldId }));
              }}
            />
          </div>
          {current.messages.length > 0 && (
            <button
              className="nx-icon-button"
              title="导出当前对话"
              aria-label="导出当前对话"
              onClick={exportConversation}
            >
              <Download size={17} />
            </button>
          )}
        </div>
        <div
          className="nx-conversation-scroll"
          ref={scrollRef}
          onScroll={() => {
            const el = scrollRef.current;
            if (el) followScroll.current = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
          }}
        >
          {!current.messages.length ? (
            <section className="nx-welcome">
              <div className="nx-welcome-heading">
                <span className="nx-eyebrow">
                  <span />
                  NONGXIN · YOUR FARMING COMPANION
                </span>
                <h1>
                  每一份耕耘，
                  <br />
                  都有<span>回应。</span>
                </h1>
                <p>从一个问题开始，和农心一起理清田里的事。</p>
              </div>
              {showArt && (
                <figure className="nx-brand-landscape">
                  <img
                    src="/brand/field-study.png"
                    alt="绿色田垄的品牌概念影像"
                    onError={() => setShowArt(false)}
                  />
                  <figcaption>
                    <span>从田间出发，向每一步行动。</span>
                    <small>品牌概念影像 · 非实测田块</small>
                  </figcaption>
                </figure>
              )}
              <div className="nx-starters">
                {STARTERS.map((s) => (
                  <button key={s.name} onClick={() => suggest(s.prompt)}>
                    <s.icon size={21} />
                    <b>
                      {s.name}
                      <ArrowRight size={15} />
                    </b>
                    <span>{s.detail}</span>
                  </button>
                ))}
              </div>
            </section>
          ) : (
            <div className="nx-message-list" role="log" aria-label="对话记录" aria-live="polite">
              {current.summary && (
                <details className="nx-summary-note">
                  <summary>
                    {summaryCountText(current.summarizedCount ?? 0)}（点击查看摘要全文）
                  </summary>
                  <p>{current.summary}</p>
                  <p className="nx-summary-hint">
                    摘要由本地规则抽取，可能遗漏细节；关键事实以你的原话为准。它只用于补齐已经滚出“最近
                    20 条”窗口的早前消息，不替代这些消息本身。
                  </p>
                </details>
              )}
              {current.messages
                .filter(
                  (m) =>
                    !(
                      sending &&
                      m.id === current.messages.at(-1)?.id &&
                      m.status === 'interrupted'
                    ),
                )
                .map((m) => (
                  <article key={m.id} className={`nx-message is-${m.role}`}>
                    {m.role === 'assistant' && <BrandMark small />}
                    <div className="nx-message-body">
                      <div className="nx-message-name">
                        {m.role === 'assistant' ? '农心' : '我'}
                        {m.role === 'assistant' && <span>AI 助手</span>}
                      </div>
                      <div className="nx-message-content">
                        {m.images?.length ? (
                          <ul className="nx-image-strip is-message" aria-label="随本条发送的照片">
                            {m.images.map((image) => (
                              <li key={image.id}>
                                <img
                                  src={image.url}
                                  alt={`拍摄的照片（${image.width}×${image.height}）`}
                                />
                              </li>
                            ))}
                          </ul>
                        ) : null}
                        <MiniMd text={m.content} />
                        {m.status === 'interrupted' && (
                          <p className="nx-interrupted">
                            回答未完成 · 这部分内容不会作为完整答案带入后续对话。
                          </p>
                        )}
                        {m.degraded && (
                          <p className="nx-degraded">
                            回答部分完成 ·
                            工具结果已保留在下方卡片中，可直接使用；也可以重试这条问题获得完整回答。
                          </p>
                        )}
                        {m.requestContext && (
                          <details className="nx-message-context">
                            <summary>本条使用的信息</summary>
                            <p>
                              田块：{m.requestContext.field?.name || '未关联'}；天气：
                              {m.requestContext.weather
                                ? `${m.requestContext.weather.location || '所选位置'} · ${m.requestContext.weather.observedAt || '数据时间未提供'}`
                                : '未附带'}
                              。重试沿用这份快照，不自动替换为之后修改的数据。
                            </p>
                          </details>
                        )}
                        {m.attachedData && (
                          <details className="nx-message-data">
                            <summary>已附加农情数据 · {m.attachedData.length} 字符</summary>
                            <pre>{m.attachedData}</pre>
                          </details>
                        )}
                      </div>
                      {m.plan && (
                        <PlanCard
                          plan={m.plan}
                          disabled={sending || planBusy || !ready || m.id !== latestPlanId}
                          superseded={m.id !== latestPlanId}
                          addedItemIds={tasks
                            .filter((t) => t.sourceMessageId === m.id && t.planItemId)
                            .map((t) => t.planItemId as string)}
                          onAdd={() => {
                            setPlanTarget(m);
                            setPlanError('');
                          }}
                        />
                      )}
                      {m.risk && <RiskCard risk={m.risk} />}
                      {m.sources?.length ? <SourceCard sources={m.sources} /> : null}
                      {m.clarify && (
                        <ClarifyCard
                          clarify={m.clarify}
                          answers={clarifyAnswers[m.id] || {}}
                          disabled={sending || !ready || !!saveError}
                          completed={hasClarifyFollowUp(current.messages, m.id)}
                          onAnswer={(index, answer) =>
                            setClarifyAnswers((previous) => ({
                              ...previous,
                              [m.id]: setClarifyAnswer(previous[m.id] || {}, index, answer),
                            }))
                          }
                          onSubmit={async (reply) => {
                            if (hasClarifyFollowUp(current.messages, m.id)) return;
                            const accepted = await send(false, reply);
                            if (!accepted)
                              throw new Error(
                                '本次尚未提交，请检查模型设置或连接状态后再试。答案已保留。',
                              );
                          }}
                        />
                      )}
                    </div>
                  </article>
                ))}
              {sending && (
                <div className="nx-thinking" role="status">
                  <LoaderCircle size={16} className="spin" />
                  <span>{streamStatus}</span>
                  {previewLine && <span className="nx-thinking-preview">{previewLine}</span>}
                  <button onClick={() => abortRef.current?.abort()}>停止回答</button>
                </div>
              )}
            </div>
          )}
        </div>
        <div className="nx-composer-wrap">
          {(chatError || retryMessages(current.messages)) && (
            <div className="nx-chat-error" role="alert">
              <span>{chatError || incompleteHint}</span>
              {retryMessages(current.messages) && (
                <button
                  disabled={!!saveError || conversationBusy}
                  onClick={() => void send(true)}
                >
                  重试这条问题
                </button>
              )}
            </div>
          )}
          {saveError && (
            <div className="nx-chat-error" role="alert">
              <span>回答尚未保存到数据库：{saveError}。请勿刷新页面。</span>
              <button
                onClick={() => void persist(current).catch((e) => setSaveError(errorText(e)))}
              >
                重试保存
              </button>
            </div>
          )}
          <label className="nx-weather-consent">
            <input
              type="checkbox"
              checked={includeWeather && !!context}
              disabled={sending || !context}
              onChange={(e) => setIncludeWeather(e.target.checked)}
            />
            <span>
              {context
                ? `随问题发送${placeDisplayName(context.location).label}的天气（不代表田块位置）`
                : '未附带天气 · 可在侧栏获取后勾选'}
            </span>
          </label>
          <form
            className="nx-composer"
            onSubmit={(e) => {
              e.preventDefault();
              void send();
            }}
            onDrop={(e) => {
              const files = imageFilesFrom(e.dataTransfer?.files);
              if (!files.length) return;
              e.preventDefault();
              void addPhotos(files);
            }}
            onDragOver={(e) => {
              if (imageFilesFrom(e.dataTransfer?.items).length) e.preventDefault();
            }}
          >
            {queued && (
              <div className="nx-queued" role="status">
                <span>
                  已排队：{queued}，本轮回答结束后自动发送。
                </span>
                <button type="button" onClick={cancelQueued}>
                  取消排队
                </button>
              </div>
            )}
            <ImageStrip images={images} onChange={setImages} disabled={sending} />
            <label className="sr-only" htmlFor="chat-input">
              向农心提问
            </label>
            <textarea
              id="chat-input"
              ref={inputRef}
              value={query}
              maxLength={850}
              rows={2}
              disabled={!!saveError}
              placeholder={
                sending
                  ? '可以先把下一条写好；现在发送会排队，等这轮回答结束自动发出。'
                  : activeField
                    ? `关于${activeField.name}，有什么想聊的？`
                    : '说说田里的情况，或者问一个农业问题…（可 Ctrl+V 粘贴截图）'
              }
              onChange={(e) => setQuery(e.target.value)}
              onPaste={handlePaste}
              onKeyDown={(e) => {
                if (
                  e.key === 'Enter' &&
                  !e.shiftKey &&
                  !e.nativeEvent.isComposing &&
                  e.keyCode !== 229
                ) {
                  e.preventDefault();
                  void send();
                }
              }}
            />
            <div className="nx-composer-tools">
              <DataAttach
                attached={attachment}
                onAttach={setAttachment}
                onClear={() => setAttachment(null)}
                disabled={sending}
              />
              <VoiceInput
                disabled={sending || !ready}
                onText={(text) =>
                  setQuery((previous) => (previous.trim() ? `${previous.trim()} ${text}` : text))
                }
              />
              <ImageAttach
                images={images}
                disabled={sending || !ready}
                visionReady={visionReady}
                onFiles={(files) => void addPhotos(files)}
                error={imageError}
                fieldId={activeField?.id ?? ''}
                fieldName={activeField?.name ?? ''}
              />
              <span className="nx-composer-hint">
                {imageBusy
                  ? '正在处理图片…'
                  : attachment || images.length
                    ? `待发送${attachment ? ' · 农情数据' : ''}${images.length ? ` · ${images.length} 张照片` : ''}`
                    : sending
                      ? '生成中可以继续输入；发送会排队，回答结束后自动发出'
                      : 'Enter 发送 · Shift + Enter 换行'}
              </span>
              {sending && !query.trim() ? (
                <button
                  className="nx-send"
                  type="button"
                  aria-label="停止回答"
                  onClick={() => abortRef.current?.abort()}
                >
                  <Square size={16} />
                </button>
              ) : (
                <button
                  className="nx-send"
                  type="submit"
                  aria-label={sending ? '排队发送下一条' : '发送问题'}
                  disabled={!query.trim() || !ready || !!saveError || conversationBusy || imageBusy}
                >
                  <ArrowUp size={20} />
                </button>
              )}
              {activeField && fieldPhotos.length > 0 && (
                <label className="nx-field-photo-toggle">
                  <input
                    type="checkbox"
                    checked={autoFieldPhotos}
                    disabled={sending}
                    onChange={(e) => setAutoFieldPhotos(e.target.checked)}
                  />
                  <span>
                    带上最近 {Math.min(3, fieldPhotos.length)} 张田间照片（
                    {fieldPhotos
                      .slice(0, 3)
                      .map((photo) => (photo.observedAt || photo.createdAt || '').slice(5))
                      .join('、')}
                    ）
                  </span>
                </label>
              )}
            </div>
          </form>
          <p className="nx-composer-foot">
            <ShieldCheck size={12} />
            不把未知当事实。AI 建议供参考，重要农事请结合现场判断。
          </p>
        </div>
      </div>
      <aside className="nx-context-rail">
        <div
          className={`nx-resizer is-rail${rail.resizing ? ' is-active' : ''}`}
          role="separator"
          aria-orientation="vertical"
          aria-label="调整右侧栏宽度"
          aria-valuenow={rail.width}
          aria-valuemin={rail.min}
          aria-valuemax={rail.max}
          tabIndex={0}
          title="拖动调整宽度（←/→ 微调，双击恢复默认）"
          onPointerDown={rail.startResize}
          onKeyDown={rail.onKeyDown}
          onDoubleClick={rail.reset}
        />
        {railVisible && (
          <ContextPanel
            context={context}
            candidate={pendingContext}
            now={now}
            busy={contextBusy}
            error={contextError}
            onLocate={locate}
            onConfirmLocation={confirmLocation}
            onSearch={searchCity}
            onClear={clearContext}
          />
        )}
        <div className="nx-rail-section">
          <span className="nx-eyebrow">CONTEXT</span>
          <h3>下条问题的背景</h3>
          <p>
            每条问题保存独立快照。较长对话仅携带最近 20 条有效消息；
            {current.summary
              ? `更早的 ${current.summarizedCount ?? 0} 条已压缩成摘要随请求发送。`
              : '更早的消息在接近上限时会自动压缩成摘要。'}
          </p>
          <dl>
            <div>
              <dt>关联田块</dt>
              <dd>{activeField?.name || '未关联'}</dd>
            </div>
            <div>
              <dt>种植作物</dt>
              <dd>{activeField?.crop || '未提供'}</dd>
            </div>
            <div>
              <dt>播种日期</dt>
              <dd>{activeField?.sowDate || '未提供'}</dd>
            </div>
            <div>
              <dt>天气背景</dt>
              <dd>
                {includeWeather && context ? placeDisplayName(context.location).label : '不附带'}
              </dd>
            </div>
          </dl>
          {!activeField && (
            <button className="nx-text-button" onClick={() => go('fields')}>
              管理田块档案 <ArrowRight size={14} />
            </button>
          )}
        </div>
        <div className="nx-rail-section">
          <span className="nx-eyebrow">NEXT STEP</span>
          <h3>从建议，到行动</h3>
          <ol className="nx-workflow">
            <li>
              <span>1</span>
              <div>
                <b>说清情况</b>
                <small>描述问题，按需补充数据</small>
              </div>
            </li>
            <li>
              <span>2</span>
              <div>
                <b>一起判断</b>
                <small>分清已知、依据与待确认</small>
              </div>
            </li>
            <li>
              <span>3</span>
              <div>
                <b>确认后行动</b>
                <small>把建议加入任务，记录复查</small>
              </div>
            </li>
          </ol>
        </div>
        <div className="nx-rail-task">
          <CheckCheck size={20} />
          <div>
            <b>{ready ? `${pendingTasks.length} 项待办农事` : '待办尚未载入'}</b>
            <p>{pendingTasks.length ? '留一点时间，看看下一步。' : '有了计划，再从这里开始。'}</p>
          </div>
          <button aria-label="查看农事任务" onClick={() => go('tasks')}>
            <ArrowRight size={16} />
          </button>
        </div>
      </aside>
    </div>
  );
}
