/**
 * Call 시뮬레이터 웹 UI 서버 (:8085)
 * public/index.html 을 서빙하고 API(:8084) 와 통신
 */
const express = require('express');
const path = require('path');

const app = express();
const PORT = 8085;

app.use(express.static(path.join(__dirname, 'public')));

app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.listen(PORT, () => {
  console.log(`[CALL-SIM UI] listening on :${PORT}  →  http://localhost:${PORT}`);
});
