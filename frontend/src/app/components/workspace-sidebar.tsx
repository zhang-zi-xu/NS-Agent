import type { CSSProperties } from 'react';
import { ArrowUpRight, ChevronDown, MessageSquare, Plus } from 'lucide-react';

import { ContextPanel } from '@/features/context/components/context-panel';

import { ConversationActions } from '@/features/chat/components/conversation-actions';

import { BrandMark } from '@/shared/ui/brand-mark';
import { NAV } from '../navigation';
import type { WorkspaceModel } from '../use-workspace';

export function WorkspaceSidebar({ workspace }: { workspace: WorkspaceModel }) {
  const {
    conversations,
    current,
    sending,
    conversationBusy,
    saveError,
    fresh,
    openConversation,
    renameConversation,
    deleteConversation,
    view,
    setMobileNav,
    mobileNav,
    ready,
    loading,
    sidebar,
    railVisible,
    pendingTasks,
    collapsedGroups,
    setCollapsedGroups,
    historyGroups,
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
    <>
      {mobileNav && (
        <button
          className="nx-nav-scrim"
          aria-label="收起导航"
          onClick={() => setMobileNav(false)}
        />
      )}
      <aside
        className={`nx-sidebar${mobileNav ? ' is-open' : ''}`}
        style={{ '--nx-sidebar-width': `${sidebar.width}px` } as CSSProperties}
      >
        <a
          className="nx-brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            go('chat');
          }}
        >
          <BrandMark />
          <span>
            <strong>
              农心 <i>Agent</i>
            </strong>
            <small>把农事，放在心上。</small>
          </span>
        </a>
        <button
          className="nx-new-chat"
          disabled={sending || !!saveError || conversationBusy}
          onClick={() => fresh()}
        >
          <Plus size={18} />
          开启新对话
          <span className="nx-new-chat-go">
            <ArrowUpRight size={16} strokeWidth={2.2} />
          </span>
        </button>
        <div className="nx-nav-label">工作空间</div>
        <nav aria-label="主导航">
          {NAV.map((n) => (
            <button
              key={n.id}
              className={view === n.id ? 'is-active' : ''}
              onClick={() => go(n.id)}
            >
              <n.icon size={18} />
              <span>{n.label}</span>
              {n.id === 'tasks' && pendingTasks.length > 0 && <b>{pendingTasks.length}</b>}
            </button>
          ))}
        </nav>
        <section className="nx-history" aria-label="最近对话">
          <div className="nx-nav-label">
            最近对话 <span>{ready ? conversations.length : '—'}</span>
          </div>
          <div className="nx-history-list" role="region" aria-label="最近对话列表" tabIndex={0}>
            {historyGroups.length ? (
              historyGroups.map((group) => {
                const collapsed = !!collapsedGroups[group.key];
                return (
                  <section className="nx-history-group" key={group.key}>
                    <button
                      type="button"
                      className="nx-history-group-head"
                      aria-expanded={!collapsed}
                      aria-label={`${group.label}的对话（${group.items.length} 段）`}
                      onClick={() =>
                        setCollapsedGroups((prev) => ({ ...prev, [group.key]: !prev[group.key] }))
                      }
                    >
                      <ChevronDown size={12} className={collapsed ? 'is-collapsed' : ''} />
                      <span className="nx-history-group-name">{group.label}</span>
                      {group.hint && <span className="nx-history-group-hint">{group.hint}</span>}
                      <b>{group.items.length}</b>
                    </button>
                    {!collapsed && (
                      <div className="nx-history-group-rows">
                        {group.items.map((c) => (
                          <div
                            key={c.id}
                            className={`nx-history-row${current.id === c.id ? ' is-active' : ''}`}
                          >
                            <button
                              title={c.title}
                              aria-current={current.id === c.id ? 'page' : undefined}
                              disabled={sending || !!saveError || conversationBusy}
                              onClick={() => openConversation(c)}
                            >
                              <MessageSquare size={13} />
                              <span>{c.title}</span>
                            </button>
                            <ConversationActions
                              conversation={c}
                              disabled={sending || !!saveError || conversationBusy}
                              onRename={renameConversation}
                              onDelete={deleteConversation}
                            />
                          </div>
                        ))}
                      </div>
                    )}
                  </section>
                );
              })
            ) : (
              <p>你的对话会保存在这里</p>
            )}
          </div>
        </section>
        {!railVisible && (
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
        <div className="nx-sidebar-bottom">
          <span className={`nx-connection-dot${ready ? ' is-online' : ''}`} />
          {loading ? '连接工作区…' : ready ? 'Java 服务已连接' : '工作区未连接'}
          <span>v1.0</span>
        </div>
      </aside>
      <div
        className={`nx-resizer${sidebar.resizing ? ' is-active' : ''}`}
        role="separator"
        aria-orientation="vertical"
        aria-label="调整侧边栏宽度"
        aria-valuenow={sidebar.width}
        aria-valuemin={sidebar.min}
        aria-valuemax={sidebar.max}
        tabIndex={0}
        title="拖动调整宽度（←/→ 微调，双击恢复默认）"
        onPointerDown={sidebar.startResize}
        onKeyDown={sidebar.onKeyDown}
        onDoubleClick={sidebar.reset}
      />
    </>
  );
}
