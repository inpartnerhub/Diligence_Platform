"use client";

import { Flex, Space, theme } from "antd";
import type { CSSProperties, ReactNode } from "react";

/**
 * A row for antd's `Listy` (which replaced the deprecated `List` in antd 6.6): title and description on the left,
 * actions on the right, as `List.Item` with `List.Item.Meta` laid it out. Falsy actions are skipped.
 */
export function ListRow({
  title,
  description,
  actions,
  onClick,
  style,
}: Readonly<{
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode[];
  onClick?: () => void;
  style?: CSSProperties;
}>) {
  const { token } = theme.useToken();
  const shown = (actions ?? []).filter(Boolean);
  return (
    <Flex align="center" gap={token.marginMD} onClick={onClick} style={style}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ color: token.colorText }}>{title}</div>
        {description !== undefined && description !== null && (
          <div style={{ color: token.colorTextDescription, marginTop: token.marginXXS }}>{description}</div>
        )}
      </div>
      {shown.length > 0 && <Space size="small">{shown}</Space>}
    </Flex>
  );
}
