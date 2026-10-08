import type { ChatMessage, ClarifyArgs } from '@/features/chat/types';

export type ClarifyAnswers = Record<number, string>;

const customAnswerOptions = new Set([
  '我写给您',
  '我写给你',
  '我来写',
  '我填写',
  '我来填写',
  '我自己填写',
  '我补充',
  '我补充一下',
  '自行填写',
  '自行填写或补充',
  '自己填写',
  '手动填写',
  '手动输入',
  '自定义',
  '自定义回答',
  '其他',
  '其它',
]);

/** These labels request free text; they do not provide facts about the question. */
export function isClarifyCustomOption(option: string): boolean {
  const text = option
    .trim()
    .replace(/\s+/g, '')
    .replace(/[。.!！?？]+$/, '');
  return (
    customAnswerOptions.has(text) ||
    /^(?:其他|其它)[（(](?:请)?(?:自行|手动)?(?:填写|补充|说明)[）)]$/.test(text)
  );
}

export function isClarifyAnswerComplete(answer?: string): boolean {
  return !!answer?.trim() && !isClarifyCustomOption(answer);
}

export function getClarifyItems(clarify: ClarifyArgs) {
  return (Array.isArray(clarify.items) ? clarify.items : [])
    .filter((item) => item && typeof item.question === 'string' && item.question.trim())
    .map((item) => ({
      question: item.question!.trim(),
      hint: typeof item.hint === 'string' ? item.hint : '',
      options: [
        ...new Set(
          (Array.isArray(item.options) ? item.options : [])
            .filter((option): option is string => typeof option === 'string' && !!option.trim())
            .map((option) => option.trim()),
        ),
      ],
    }));
}

export function setClarifyAnswer(
  answers: ClarifyAnswers,
  index: number,
  answer: string,
): ClarifyAnswers {
  return { ...answers, [index]: answer };
}

export function formatClarifyReply(clarify: ClarifyArgs, answers: ClarifyAnswers): string {
  const items = getClarifyItems(clarify);
  if (!items.length) throw new Error('这张确认卡没有可回答的问题。');
  if (items.some((_, index) => !answers[index]?.trim())) {
    throw new Error('请逐题选择或填写答案；不清楚的可以选择“暂不确定”。');
  }
  if (items.some((_, index) => isClarifyCustomOption(answers[index]))) {
    throw new Error('选择填写选项后，请补充实际内容；不清楚的可以选择“暂不确定”。');
  }
  // Assemble only the user's actual answers; do not have a model invent connective facts.
  const reply =
    '我补充的信息如下：\n\n' +
    items
      .map((item, index) => `${index + 1}. 问题：${item.question}\n回答：${answers[index].trim()}`)
      .join('\n\n') +
    '\n\n请结合以上信息继续分析，并给出下一步建议。';
  if (reply.length > 4000) throw new Error('补充内容超过 4,000 字符，请精简填写的答案后再提交。');
  return reply;
}

/** Retire a card once the user has continued the conversation, including a failed request awaiting retry. */
export function hasClarifyFollowUp(messages: ChatMessage[], messageId: string): boolean {
  const index = messages.findIndex((message) => message.id === messageId);
  return index >= 0 && messages.slice(index + 1).some((message) => message.role === 'user');
}
