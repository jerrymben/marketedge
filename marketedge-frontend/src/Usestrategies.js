import { useState, useEffect } from 'react';
import { fetchStrategies, fetchHealth } from '../services/api';

export function useStrategies() {
  const [strategies, setStrategies] = useState([]);
  const [loading, setLoading]       = useState(false);

  useEffect(() => {
    setLoading(true);
    fetchStrategies()
      .then((data) => setStrategies(Array.isArray(data) ? data : []))
      .catch(() => setStrategies([]))
      .finally(() => setLoading(false));
  }, []);

  return { strategies, loading };
}

export function useHealth() {
  const [health, setHealth] = useState(null);

  useEffect(() => {
    const check = () =>
      fetchHealth()
        .then(setHealth)
        .catch(() => setHealth({ status: 'DOWN' }));

    check();
    const id = setInterval(check, 15_000);
    return () => clearInterval(id);
  }, []);

  return health;
}