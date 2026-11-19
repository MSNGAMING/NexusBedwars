import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = {
  title: "NexusBedwars – Architecture Blueprint",
  description:
    "Enterprise-grade Bedwars plugin for Paper, Purpur and Folia: architecture, Folia scheduler abstraction, arena state machine, setup wizard and database manager.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body className="bg-[#070a11] text-slate-200 antialiased">{children}</body>
    </html>
  );
}
