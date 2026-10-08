import { useRef, useState } from 'react';
import { ArrowUp, Check, CircleHelp, LoaderCircle } from 'lucide-react';
import type { ClarifyArgs } from '@/features/chat/types';
import {
  formatClarifyReply,
  getClarifyItems,
  isClarifyAnswerComplete,
  type ClarifyAnswers,
} from '@/features/chat/clarification';
import { ClarifyQuestion } from './clarify-question';

export function ClarifyCard({
  clarify,
  answers,
  onAnswer,
  onSubmit,
  disabled,
  completed,
}: {
  clarify: ClarifyArgs;
  answers: ClarifyAnswers;
  onAnswer: (index: number, answer: string) => void;
  onSubmit: (reply: string) => Promise<void>;
  disabled?: boolean;
  completed?: boolean;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const submittingRef = useRef(false);
  const items = getClarifyItems(clarify);
  const answeredCount = items.filter((_, index) => isClarifyAnswerComplete(answers[index])).length;
  const locked = !!(disabled || completed || submitting);

  async function submit() {
    if (locked || submittingRef.current) return;
    setError('');
    try {
      const reply = formatClarifyReply(clarify, answers);
      submittingRef.current = true;
      setSubmitting(true);
      await onSubmit(reply);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '未能提交，请重试。');
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  return (
    <section className="nx-clarify">
      <header>
        <CircleHelp size={16} />
        <b>补充一点情况</b>
      </header>
      {clarify.intro && <p>{clarify.intro}</p>}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        {items.map((item, index) => (
          <ClarifyQuestion
            key={index}
            item={item}
            index={index}
            answer={answers[index] || ''}
            locked={locked}
            onAnswer={(answer) => {
              setError('');
              onAnswer(index, answer);
            }}
          />
        ))}
        {error && (
          <p className="nx-error" role="alert">
            {error}
          </p>
        )}
        <footer className="nx-clarify-footer">
          <p aria-live="polite">
            {completed
              ? '已继续对话，请查看下方消息。'
              : `已回答 ${answeredCount} / ${items.length} 项，提交后统一发送。`}
          </p>
          <button
            className="nx-button is-primary"
            type="submit"
            disabled={locked || !items.length || answeredCount !== items.length}
          >
            {completed ? (
              <Check size={15} />
            ) : submitting ? (
              <LoaderCircle size={15} className="spin" />
            ) : (
              <ArrowUp size={15} />
            )}
            {completed ? '已继续对话' : submitting ? '正在提交…' : '提交并继续'}
          </button>
        </footer>
      </form>
    </section>
  );
}
