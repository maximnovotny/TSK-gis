import React from "react";
import { createRoot } from "react-dom/client";
import "./monaco-setup";
import App from "./App";
import "./index.css";

const container = document.getElementById("root");
if (!container) throw new Error("Missing #root");

createRoot(container).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
