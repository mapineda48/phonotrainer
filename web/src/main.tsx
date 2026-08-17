import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import App from "./App";
import { JobsProvider } from "./jobs/JobsProvider";
import "./styles.css";

const root = document.getElementById("root");
if (!root) throw new Error("#root is missing from index.html");

createRoot(root).render(
  <StrictMode>
    <JobsProvider>
      <App />
    </JobsProvider>
  </StrictMode>,
);
