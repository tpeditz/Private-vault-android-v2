import React, { useEffect, useState } from "react";
import QRCode from "qrcode";

interface QrCodeSvgProps {
  value: string;
  label?: string;
}

export const QrCodeSvg: React.FC<QrCodeSvgProps> = ({ value, label = "Authenticator QR Code" }) => {
  const [dataUrl, setDataUrl] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    QRCode.toDataURL(value, { width: 220, margin: 1 })
      .then((url) => {
        if (!cancelled) setDataUrl(url);
      })
      .catch(() => {
        if (!cancelled) setDataUrl(null);
      });
    return () => {
      cancelled = true;
    };
  }, [value]);

  if (!dataUrl) {
    return <p className="muted">Generating local QR code...</p>;
  }

  return (
    <img
      src={dataUrl}
      alt={label}
      width={200}
      height={200}
      style={{ borderRadius: 12, background: "#fff", padding: 8 }}
    />
  );
};
