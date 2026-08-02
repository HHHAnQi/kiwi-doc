import { useCallback, useRef, useState } from 'react';
import { useDocStore } from '../store/useDocStore';
import { cn } from '../lib/cn';
import type { UploadOptions } from '../api/documents';

interface Props {
  // 可选默认元数据 (sidebar 上传时由折叠区填)
  defaultMeta?: UploadOptions;
}

// 拖拽上传区. 用 hidden <input type=file> 触发 + dragenter/dragover/drop 双路径
export function UploadDropzone({ defaultMeta = {} }: Props) {
  const upload = useDocStore((s) => s.upload);
  const uploading = useDocStore((s) => s.uploading);
  const [dragOver, setDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleFiles = useCallback(
    (files: FileList | null) => {
      if (!files || files.length === 0) return;
      // 串行 upload, 防并发 → chat-app sync 模式 embed 单线程会被打爆
      Array.from(files).reduce<Promise<void>>(
        (prev, file) => prev.then(() => upload(file, defaultMeta)),
        Promise.resolve(),
      );
    },
    [upload, defaultMeta],
  );

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    handleFiles(e.dataTransfer.files);
  };

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => inputRef.current?.click()}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') inputRef.current?.click();
      }}
      onDragOver={(e) => {
        e.preventDefault();
        setDragOver(true);
      }}
      onDragLeave={() => setDragOver(false)}
      onDrop={onDrop}
      className={cn(
        'flex flex-col items-center justify-center gap-1 rounded-lg border-2 border-dashed px-3 py-4 cursor-pointer transition-colors',
        dragOver
          ? 'border-brand-500 bg-brand-50'
          : 'border-slate-300 hover:border-brand-400 hover:bg-brand-50/40',
      )}
    >
      <div className="text-2xl">📥</div>
      <div className="text-xs text-slate-600 text-center">
        拖入或<span className="text-brand-600 font-medium">点击</span>上传
        <br />
        PDF / Markdown / HTML
      </div>
      {Object.keys(uploading).length > 0 && (
        <div className="mt-1 text-xs text-brand-600">
          上传中... ({Object.keys(uploading).length})
        </div>
      )}
      <input
        ref={inputRef}
        type="file"
        multiple
        accept=".pdf,.md,.markdown,.html,.htm,text/markdown,text/html,application/pdf"
        className="hidden"
        onChange={(e) => {
          handleFiles(e.target.files);
          // 允许重复上传同一文件
          e.target.value = '';
        }}
      />
    </div>
  );
}
