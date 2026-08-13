import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Board } from './Board.tsx'
import './style.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Board />
  </StrictMode>,
)
