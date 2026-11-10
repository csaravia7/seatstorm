import { useEffect } from 'react';
import { create } from 'zustand';
import { api } from '../api/endpoints';

export const DEFAULT_CONCERT_NAME = 'Neon Tide · Arena Norte';
export const MAX_CONCERT_NAME = 80;

interface ConcertState {
  name: string;
  setName: (name: string) => void;
}

/** The event name is the main label of every screen; it is edited from the console and stored in event-catalog. */
export const useConcertStore = create<ConcertState>((set) => ({
  name: DEFAULT_CONCERT_NAME,
  setName: (name) => set({ name: name.trim() || DEFAULT_CONCERT_NAME }),
}));

export const useConcertName = () => useConcertStore((s) => s.name);

/** Loads the name at start and re-reads it every 15 s so open screens pick up a rename. Mounted once in <App>. */
export function useConcertSync() {
  const name = useConcertName();
  useEffect(() => {
    document.title = `SeatStorm · ${name}`;
  }, [name]);
  useEffect(() => {
    let alive = true;
    const load = () =>
      api.concert().then((c) => alive && c.name && useConcertStore.getState().setName(c.name)).catch(() => undefined);
    void load();
    const t = setInterval(() => void load(), 15_000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, []);
}
