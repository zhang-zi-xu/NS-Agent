import { useEffect, useId, useRef, useState } from 'react';
import { Check } from 'lucide-react';
import {
  isClarifyAnswerComplete,
  isClarifyCustomOption,
  type getClarifyItems,
} from '@/features/chat/clarification';

export function ClarifyQuestion({
  item,
  index,
  answer,
  locked,
  onAnswer,
}: {
  item: ReturnType<typeof getClarifyItems>[number];
  index: number;
  answer: string;
  locked: boolean;
  onAnswer: (answer: string) => void;
}) {
  const id = useId();
  const options = [...new Set([...item.options, '暂不确定'])];
  const freeText = !options.includes(answer) && isClarifyAnswerComplete(answer) ? answer : '';
  const [customOption, setCustomOption] = useState<string | null>(() =>
    isClarifyCustomOption(answer)
      ? answer
      : freeText
        ? item.options.find(isClarifyCustomOption) || null
        : null,
  );
  const [draft, setDraft] = useState(freeText);
  const [expanded, setExpanded] = useState(!item.options.length || !!freeText || !!customOption);
  const [focusRequest, setFocusRequest] = useState(0);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const complete = isClarifyAnswerComplete(answer);
  const inputValue = isClarifyCustomOption(answer) ? '' : answer;

  useEffect(() => {
    if (focusRequest) inputRef.current?.focus();
  }, [focusRequest]);

  function selectOption(option: string) {
    if (isClarifyCustomOption(option)) {
      setCustomOption(option);
      setExpanded(true);
      setFocusRequest((previous) => previous + 1);
      onAnswer(freeText || draft);
    } else {
      setCustomOption(null);
      setExpanded(false);
      onAnswer(option);
    }
  }

  return (
    <fieldset className="nx-clarify-question" disabled={locked}>
      <legend>
        {index + 1}. {item.question}
      </legend>
      {item.hint && <p className="nx-muted">{item.hint}</p>}
      <div className="nx-clarify-options">
        {options.map((option) => {
          const selected = customOption ? customOption === option : answer === option;
          return (
            <label key={option} className={`nx-clarify-option${selected ? ' is-selected' : ''}`}>
              <input
                type="radio"
                name={`${id}-question`}
                value={option}
                checked={selected}
                onClick={() => {
                  if (selected && isClarifyCustomOption(option)) selectOption(option);
                }}
                onChange={() => selectOption(option)}
              />
              <span>{option}</span>
              {selected && <Check size={13} aria-hidden="true" />}
            </label>
          );
        })}
      </div>
      <details className="nx-clarify-custom" open={expanded}>
        <summary
          onClick={(event) => {
            event.preventDefault();
            setExpanded((previous) => !previous);
          }}
        >
          自行填写或补充
        </summary>
        <label className="sr-only" htmlFor={`${id}-answer`}>
          {item.question}的回答
        </label>
        <textarea
          ref={inputRef}
          id={`${id}-answer`}
          rows={3}
          maxLength={1000}
          value={inputValue}
          placeholder="请在这里填写实际情况；不清楚的可以说明暂不确定。"
          onChange={(event) => {
            setDraft(event.target.value);
            setCustomOption(
              (previous) => previous || item.options.find(isClarifyCustomOption) || null,
            );
            onAnswer(event.target.value);
          }}
        />
      </details>
      {complete ? (
        <p className="nx-clarify-answer">当前回答：{answer}</p>
      ) : customOption ? (
        <p className="nx-clarify-answer" role="status">
          请在上方填写实际情况，填写后才能提交。
        </p>
      ) : null}
    </fieldset>
  );
}
