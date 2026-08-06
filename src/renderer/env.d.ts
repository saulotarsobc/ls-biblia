/// <reference types="vite/client" />
import type { Api } from '../preload/index.ts';

declare global {
  interface Window {
    api: Api;
  }
}

export {};
