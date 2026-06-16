import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  logout,
  selectAuthUser,
  selectCanAccessAdmin,
  selectIsAuthenticated,
  selectIsAuthLoading,
} from '../store/slices/authSlice';

export function useAuth() {
  const dispatch = useAppDispatch();
  const user = useAppSelector(selectAuthUser);
  const isLoading = useAppSelector(selectIsAuthLoading);
  const isAuthenticated = useAppSelector(selectIsAuthenticated);
  const canAccessAdmin = useAppSelector(selectCanAccessAdmin);

  return {
    user,
    userData: user,
    isLoading,
    isAuthenticated,
    canAccessAdmin,
    signOut: () => dispatch(logout()),
  };
}
