export function fileSize(bytes) {
  const unit = bytes >= 1024 * 1024 ? 1024 * 1024 : bytes >= 1024 ? 1024 : 1;
  return (
    new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 1 }).format(
      bytes / unit,
    ) + (unit === 1 ? " B" : unit === 1024 ? " KB" : " MB")
  );
}
