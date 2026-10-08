import {
  Check,
  Link2,
  LoaderCircle,
  Menu,
  RefreshCw,
  Settings2,
  Smartphone,
  X,
} from 'lucide-react';

import { FieldsView } from '@/features/fields/components/fields-view';
import { TasksView } from '@/features/tasks/components/tasks-view';
import { KnowledgeView } from '@/features/knowledge/components/knowledge-view';

import { NAV } from './navigation';
import { useWorkspace } from './use-workspace';

import { WorkspaceSidebar } from './components/workspace-sidebar';
import { WorkspaceChat } from './components/workspace-chat';
import { WorkspaceDialogs } from './components/workspace-dialogs';

/** Layout composition only: feature hooks own all mutable business lifecycles. */
export function Workspace() {
  const workspace = useWorkspace();
  const {
    fresh,
    suggest,
    analyzeField,
    view,
    setMobileNav,
    fields,
    tasks,
    knowledge,
    ready,
    loading,
    loadError,
    settings,
    setSettingsOpen,
    setMobileOpen,
    setWechatOpen,
    notice,
    setNotice,
    sidebar,
    rail,
    loadWorkspace,
    saveField,
    removeField,
    addRecord,
    saveTask,
    removeTask,
    changeTaskStatus,
    addTaskRecord,
    discussTask,
    reviewFollowUp,
  } = workspace;
  return (
    <div className={`nx-shell${sidebar.resizing || rail.resizing ? ' is-resizing' : ''}`}>
      <WorkspaceSidebar workspace={workspace} />
      <main className="nx-main">
        <header className="nx-topbar">
          <div className="nx-topbar-left">
            <button
              className="nx-icon-button nx-menu"
              aria-label="打开导航"
              onClick={() => setMobileNav(true)}
            >
              <Menu size={21} />
            </button>
            <span className="nx-breadcrumb">
              工作空间 <span>/</span> <b>{NAV.find((n) => n.id === view)?.label}</b>
            </span>
          </div>
          <div className="nx-topbar-actions">
            <span className="nx-model-label">
              {settings.apiKey
                ? settings.model
                : settings.provider === 'custom'
                  ? '尚未连接模型'
                  : '演示模型 · 服务端配置'}
            </span>
            <button
              className="nx-button is-small"
              aria-label="连接微信"
              onClick={() => setWechatOpen(true)}
            >
              <Link2 size={16} />
              <span>连接微信</span>
            </button>
            <button
              className="nx-button is-small"
              aria-label="手机访问"
              onClick={() => setMobileOpen(true)}
            >
              <Smartphone size={16} />
              <span>手机访问</span>
            </button>
            <button
              className="nx-button is-small"
              aria-label="模型设置"
              onClick={() => setSettingsOpen(true)}
            >
              <Settings2 size={16} />
              <span>模型设置</span>
            </button>
          </div>
        </header>
        {loadError && (
          <div className="nx-global-error" role="alert">
            <span>{loadError}</span>
            <button disabled={loading} onClick={() => void loadWorkspace()}>
              <RefreshCw size={14} />
              重新连接
            </button>
          </div>
        )}
        {notice && (
          <div className="nx-notice" role="status">
            <Check size={16} />
            {notice}
            <button aria-label="关闭提示" onClick={() => setNotice('')}>
              <X size={14} />
            </button>
          </div>
        )}
        {view === 'chat' ? (
          <WorkspaceChat workspace={workspace} />
        ) : (
          <div className="nx-page-scroll">
            {!ready ? (
              <div className="nx-empty">
                <LoaderCircle size={28} className={loading ? 'spin' : ''} />
                <h2>{loading ? '正在载入工作区' : '工作区尚未连接'}</h2>
                <p>连接成功后会展示数据库中的真实记录。</p>
                <button
                  className="nx-button"
                  disabled={loading}
                  onClick={() => void loadWorkspace()}
                >
                  重新连接
                </button>
              </div>
            ) : view === 'fields' ? (
              <FieldsView
                fields={fields}
                onSave={saveField}
                onDelete={removeField}
                onRecord={addRecord}
                onAsk={(id) => fresh(id)}
                onAnalyze={analyzeField}
              />
            ) : view === 'tasks' ? (
              <TasksView
                settings={settings}
                onOpenWechat={() => setWechatOpen(true)}
                tasks={tasks}
                fields={fields}
                onSave={saveTask}
                onDelete={removeTask}
                onStatus={changeTaskStatus}
                onRecord={addTaskRecord}
                onDiscuss={discussTask}
                onReviewFollowUp={reviewFollowUp}
              />
            ) : (
              <KnowledgeView entries={knowledge} onAsk={suggest} />
            )}
          </div>
        )}
      </main>
      <WorkspaceDialogs workspace={workspace} />
    </div>
  );
}
