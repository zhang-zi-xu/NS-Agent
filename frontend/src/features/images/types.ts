/** 随消息发送的图片附件（元数据来自服务端，客户端不保存 base64）。 */
export type AttachedImage = {
  id: string;
  url: string;
  mime: string;
  width: number;
  height: number;
  bytes: number;
  /** 仅本地预览用：用户选择时的文件名 */
  name?: string;
  /** 归档信息：属于哪个田块、哪一天拍的、备注；createdAt 是上传时间（缺拍摄日期时按它排序） */
  fieldId?: string;
  observedAt?: string;
  note?: string;
  createdAt?: string;
};
